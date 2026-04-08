import sys
import json
import warnings
warnings.filterwarnings("ignore")
import os
os.environ["TF_CPP_MIN_LOG_LEVEL"] = "3"

import numpy as np
import pandas as pd
import psycopg2
from sklearn.preprocessing import MinMaxScaler
from tensorflow.keras.models import Sequential
from tensorflow.keras.layers import LSTM, Dense, Dropout

DB_URL   = os.environ.get("DATABASE_URL", "")
LOOKBACK = 60   # use last 60 days to predict (more data = better)
HORIZON  = 7    # predict next 7 days

# All features from Aiven database
FEATURES = [
    "close",            # price          (Binance)
    "volume",           # volume         (Binance)
    "high",             # daily high     (Binance)
    "low",              # daily low      (Binance)
    "hl_range",         # volatility     (calculated)
    "fear_greed",       # fear/greed     (Alternative.me)
    "rsi",              # RSI            (calculated)
    "macd",             # MACD           (calculated)
    "macd_hist",        # MACD histogram (calculated)
    "bb_upper",         # Bollinger up   (calculated)
    "bb_lower",         # Bollinger down (calculated)
    "price_change_1d",  # 1 day return   (calculated)
    "price_change_7d",  # 7 day return   (calculated)
    "volume_change_1d", # volume change  (calculated)
]


def get_conn():
    return psycopg2.connect(DB_URL, sslmode="require")


def load_from_db(coin_id):
    """Load OHLCV + indicators from Aiven database"""
    conn = get_conn()
    try:
        sql = """
            SELECT date, close, volume, high, low, hl_range,
                   fear_greed, rsi, macd, macd_hist,
                   bb_upper, bb_lower,
                   price_change_1d, price_change_7d, volume_change_1d
            FROM ohlcv_data
            WHERE coin_id = %s
            ORDER BY date ASC
        """
        df = pd.read_sql(sql, conn, params=(coin_id,))
        return df
    finally:
        conn.close()


def build_model(lookback, n_features, horizon):
    model = Sequential([
        LSTM(128, return_sequences=True, input_shape=(lookback, n_features)),
        Dropout(0.2),
        LSTM(64, return_sequences=False),
        Dropout(0.2),
        Dense(32, activation="relu"),
        Dense(horizon)
    ])
    model.compile(optimizer="adam", loss="mean_squared_error")
    return model


def make_sequences(data, lookback, horizon):
    X, y = [], []
    for i in range(lookback, len(data) - horizon + 1):
        X.append(data[i - lookback:i])
        y.append(data[i:i + horizon, 0])  # 0 = close price
    return np.array(X), np.array(y)


def save_prediction_to_db(coin_id, current_price, forecast_list,
                           growth_pct, signal, advice, features_used):
    """Save prediction results back to Aiven"""
    if not DB_URL:
        return
    try:
        conn = get_conn()
        with conn.cursor() as cur:
            for item in forecast_list:
                cur.execute("""
                    INSERT INTO predictions (
                        coin_id, predicted_for, current_price,
                        predicted_price, low_price, high_price,
                        growth_percent, signal, advice, features_used
                    ) VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
                    ON CONFLICT DO NOTHING
                """, (
                    coin_id,
                    item["date"],
                    current_price,
                    item["predicted_price"],
                    item["low"],
                    item["high"],
                    growth_pct,
                    signal,
                    advice,
                    ",".join(features_used)
                ))
        conn.commit()
        conn.close()
    except Exception as e:
        print(f"Warning: Could not save prediction to DB: {e}")


def predict(coin_id, horizon=7):
    # Load data from Aiven
    if DB_URL:
        try:
            df = load_from_db(coin_id)
            if df.empty:
                return {"error": f"No data in database for {coin_id}. Run ETL first."}
        except Exception as e:
            return {"error": f"Database error for {coin_id}: {e}"}
    else:
        # Fallback to CSV if no DB connection
        csv_path = f"market_data_{coin_id}.csv"
        try:
            df = pd.read_csv(csv_path)
            df = df.rename(columns={"price": "close"})
        except FileNotFoundError:
            return {"error": f"No data found for {coin_id}."}

    df = df.dropna().reset_index(drop=True)

    if len(df) < LOOKBACK + horizon + 2:
        return {"error": f"Not enough data for {coin_id}. Need at least {LOOKBACK + horizon + 2} rows, got {len(df)}."}

    last_price = float(df["close"].iloc[-1])
    last_date  = pd.to_datetime(df["date"].iloc[-1]) if "date" in df.columns else pd.Timestamp.now()
    std        = float(df["close"].std())

    # Use available features
    available  = [f for f in FEATURES if f in df.columns]
    raw        = df[available].values.astype(float)
    n_features = raw.shape[1]

    # Scale all features 0-1
    scaler = MinMaxScaler(feature_range=(0, 1))
    scaled = scaler.fit_transform(raw)

    # Build sequences
    X, y = make_sequences(scaled, LOOKBACK, horizon)
    if len(X) == 0:
        return {"error": f"Not enough sequences for {coin_id}."}

    # Train LSTM
    model = build_model(LOOKBACK, n_features, horizon)
    model.fit(X, y, epochs=60, batch_size=16, verbose=0)

    # Predict next 7 days
    last_seq    = scaled[-LOOKBACK:].reshape(1, LOOKBACK, n_features)
    pred_scaled = model.predict(last_seq, verbose=0)[0]

    # Inverse transform price only
    dummy        = np.zeros((horizon, n_features))
    dummy[:, 0]  = pred_scaled
    pred_prices  = scaler.inverse_transform(dummy)[:, 0]

    # Build forecast list
    forecast_list = []
    for i in range(horizon):
        future_date = last_date + pd.Timedelta(days=i + 1)
        pred_price  = float(max(pred_prices[i], 0))
        forecast_list.append({
            "date":            future_date.strftime("%Y-%m-%d"),
            "predicted_price": round(pred_price, 6),
            "low":             round(max(pred_price - std * 0.4, 0), 6),
            "high":            round(pred_price + std * 0.4, 6),
        })

    predicted_day7 = forecast_list[-1]["predicted_price"]
    growth_pct     = ((predicted_day7 - last_price) / last_price) * 100

    if growth_pct > 5:
        advice = "Strong upward trend predicted — good buy window."
        signal = "BUY"
    elif growth_pct > 1:
        advice = "Moderate growth predicted — consider buying."
        signal = "BUY"
    elif growth_pct < -5:
        advice = "Significant decline predicted — consider avoiding."
        signal = "AVOID"
    elif growth_pct < -1:
        advice = "Slight decline predicted — hold or wait."
        signal = "HOLD"
    else:
        advice = "Price expected to remain stable — hold position."
        signal = "HOLD"

    # Get latest indicators for context
    latest     = df.iloc[-1]
    indicators = {}
    for col in ["rsi", "macd", "fear_greed", "volume"]:
        if col in df.columns:
            indicators[col] = round(float(latest[col]), 4)

    result = {
        "coin_id":              coin_id,
        "current_price":        round(last_price, 6),
        "predicted_price_day7": round(predicted_day7, 6),
        "growth_percent":       round(growth_pct, 4),
        "signal":               signal,
        "advice":               advice,
        "indicators":           indicators,
        "features_used":        available,
        "training_rows":        len(df),
        "forecast":             forecast_list,
    }

    # Save prediction to DB
    save_prediction_to_db(
        coin_id, last_price, forecast_list,
        growth_pct, signal, advice, available
    )

    return result


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print(json.dumps({"error": "Usage: python ai_model.py <coin_id> [horizon_days]"}))
        sys.exit(1)
    coin_id = sys.argv[1]
    horizon = int(sys.argv[2]) if len(sys.argv) > 2 else 7
    result  = predict(coin_id, horizon)
    print(json.dumps(result))

import sys
import json
import warnings
warnings.filterwarnings("ignore")
import os
os.environ["TF_CPP_MIN_LOG_LEVEL"] = "3"

import numpy as np
import pandas as pd
from sklearn.preprocessing import MinMaxScaler
from tensorflow.keras.models import Sequential
from tensorflow.keras.layers import LSTM, Dense, Dropout

LOOKBACK = 14
HORIZON  = 7

# Features the LSTM will learn from
# Source breakdown:
#   Binance     -> price, open, high, low, volume, hl_range
#   CoinGecko   -> market_cap
#   Alternative -> fear_greed_value
#   Calculated  -> rsi, macd, macd_hist, bb_upper, bb_lower,
#                  price_change_1d, price_change_7d, volume_change_1d
FEATURES = [
    "price",           # close price        (Binance)
    "volume",          # trading volume      (Binance)
    "high",            # daily high          (Binance)
    "low",             # daily low           (Binance)
    "hl_range",        # high-low range      (Binance calculated)
    "market_cap",      # market cap          (CoinGecko)
    "fear_greed_value",# fear & greed index  (Alternative.me)
    "rsi",             # RSI indicator       (calculated)
    "macd",            # MACD line           (calculated)
    "macd_hist",       # MACD histogram      (calculated)
    "bb_upper",        # Bollinger upper     (calculated)
    "bb_lower",        # Bollinger lower     (calculated)
    "price_change_1d", # 1 day return        (calculated)
    "price_change_7d", # 7 day return        (calculated)
    "volume_change_1d",# volume momentum     (calculated)
]


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
    price_idx = 0  # price is first feature
    for i in range(lookback, len(data) - horizon + 1):
        X.append(data[i - lookback:i])
        y.append(data[i:i + horizon, price_idx])
    return np.array(X), np.array(y)


def predict(coin_id, horizon=7):
    csv_path = "market_data_{}.csv".format(coin_id)

    try:
        df = pd.read_csv(csv_path)
    except FileNotFoundError:
        return {"error": "No data file found for {}. Run data_collector.py first.".format(coin_id)}

    df["timestamp"] = pd.to_datetime(df["timestamp"])
    df = df.sort_values("timestamp").reset_index(drop=True)

    # Use available features (some may be missing if collector had errors)
    available = [f for f in FEATURES if f in df.columns]
    if "price" not in available:
        return {"error": "Price column missing in data for {}.".format(coin_id)}

    df = df[available].dropna().reset_index(drop=True)

    if len(df) < LOOKBACK + horizon + 2:
        return {"error": "Not enough data for {}.".format(coin_id)}

    last_price = float(df["price"].iloc[-1])
    last_date  = pd.to_datetime(df["timestamp"].iloc[-1]) if "timestamp" in df.columns else pd.Timestamp.now()

    # Re-read with timestamp for last_date
    df_full = pd.read_csv(csv_path)
    df_full["timestamp"] = pd.to_datetime(df_full["timestamp"])
    df_full = df_full.sort_values("timestamp").reset_index(drop=True)
    last_date = df_full["timestamp"].iloc[-1]

    raw = df[available].values.astype(float)
    n_features = raw.shape[1]

    # Scale all features to 0-1
    scaler = MinMaxScaler(feature_range=(0, 1))
    scaled = scaler.fit_transform(raw)

    X, y = make_sequences(scaled, LOOKBACK, horizon)
    if len(X) == 0:
        return {"error": "Not enough sequences for {}.".format(coin_id)}

    # Train model
    model = build_model(LOOKBACK, n_features, horizon)
    model.fit(X, y, epochs=60, batch_size=8, verbose=0)

    # Predict using last LOOKBACK days
    last_seq   = scaled[-LOOKBACK:].reshape(1, LOOKBACK, n_features)
    pred_scaled = model.predict(last_seq, verbose=0)[0]

    # Inverse transform price only
    # Build a dummy array to inverse transform price column
    dummy = np.zeros((horizon, n_features))
    dummy[:, 0] = pred_scaled  # price is index 0
    pred_prices = scaler.inverse_transform(dummy)[:, 0]

    std = float(df["price"].std())

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

    # Get latest indicator values for context
    latest = df.iloc[-1]
    indicators = {}
    for col in ["rsi", "macd", "fear_greed_value", "volume"]:
        if col in df.columns:
            indicators[col] = round(float(latest[col]), 4)

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

    return {
        "coin_id":              coin_id,
        "current_price":        round(last_price, 6),
        "predicted_price_day7": round(predicted_day7, 6),
        "growth_percent":       round(growth_pct, 4),
        "signal":               signal,
        "advice":               advice,
        "indicators":           indicators,
        "features_used":        available,
        "forecast":             forecast_list,
    }


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print(json.dumps({"error": "Usage: python ai_model.py <coin_id> [horizon_days]"}))
        sys.exit(1)
    coin_id = sys.argv[1]
    horizon = int(sys.argv[2]) if len(sys.argv) > 2 else 7
    result  = predict(coin_id, horizon)
    print(json.dumps(result))
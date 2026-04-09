"""
CryptoDash Prediction Trainer
LSTM with K.clear_session() fix - no retracing warnings
Supports batch training via COIN_BATCH_START and COIN_BATCH_END env vars
Runs on GitHub Actions daily after ETL.
"""
import os
import sys
import warnings
warnings.filterwarnings("ignore")
os.environ["TF_CPP_MIN_LOG_LEVEL"] = "3"

import numpy as np
import pandas as pd
import psycopg2
from psycopg2.extras import execute_values
from datetime import datetime
from sklearn.preprocessing import MinMaxScaler

DB_URL   = os.environ.get("DATABASE_URL", "")
LOOKBACK = 60
HORIZON  = 7
KEEP_PREDICTIONS_DAYS = 30

# Batch support — split coins across two workflows
BATCH_START = int(os.environ.get("COIN_BATCH_START", "0"))
BATCH_END   = int(os.environ.get("COIN_BATCH_END",   "999"))

FEATURES = [
    "close", "volume", "high", "low", "hl_range",
    "fear_greed", "rsi", "macd", "macd_hist",
    "bb_upper", "bb_lower",
    "price_change_1d", "price_change_7d", "volume_change_1d",
]

def get_conn():
    return psycopg2.connect(DB_URL, sslmode="require")

def get_all_coins(conn):
    with conn.cursor() as cur:
        cur.execute("""
            SELECT coin_id, symbol, name, risk_tier
            FROM coins WHERE is_active = TRUE
            ORDER BY coin_id
        """)
        return cur.fetchall()

def load_coin_data(coin_id, conn):
    sql = """
        SELECT date, close, volume, high, low, hl_range,
               fear_greed, rsi, macd, macd_hist,
               bb_upper, bb_lower,
               price_change_1d, price_change_7d, volume_change_1d
        FROM ohlcv_data
        WHERE coin_id = %s
        ORDER BY date ASC
    """
    return pd.read_sql(sql, conn, params=(coin_id,))

def build_model(lookback, n_features, horizon):
    import tensorflow as tf
    import tensorflow.keras.backend as K
    K.clear_session()

    model = tf.keras.Sequential([
        tf.keras.layers.LSTM(64, return_sequences=True,
                             input_shape=(lookback, n_features)),
        tf.keras.layers.Dropout(0.2),
        tf.keras.layers.LSTM(32, return_sequences=False),
        tf.keras.layers.Dropout(0.2),
        tf.keras.layers.Dense(16, activation="relu"),
        tf.keras.layers.Dense(horizon)
    ])
    model.compile(optimizer="adam", loss="mean_squared_error")
    return model

def make_sequences(data, lookback, horizon):
    X, y = [], []
    for i in range(lookback, len(data) - horizon + 1):
        X.append(data[i - lookback:i])
        y.append(data[i:i + horizon, 0])
    return np.array(X), np.array(y)

def train_and_predict(coin_id, df):
    df         = df.dropna().reset_index(drop=True)
    available  = [f for f in FEATURES if f in df.columns]
    raw        = df[available].values.astype(float)
    n_features = raw.shape[1]
    last_price = float(df["close"].iloc[-1])
    last_date  = pd.to_datetime(df["date"].iloc[-1])
    std        = float(df["close"].std())

    scaler = MinMaxScaler(feature_range=(0, 1))
    scaled = scaler.fit_transform(raw)

    X, y = make_sequences(scaled, LOOKBACK, HORIZON)
    if len(X) == 0:
        return None

    model = build_model(LOOKBACK, n_features, HORIZON)
    model.fit(X, y, epochs=30, batch_size=32, verbose=0)

    last_seq    = scaled[-LOOKBACK:].reshape(1, LOOKBACK, n_features)
    pred_scaled = model.predict(last_seq, verbose=0)[0]

    dummy       = np.zeros((HORIZON, n_features))
    dummy[:, 0] = pred_scaled
    pred_prices = scaler.inverse_transform(dummy)[:, 0]

    import tensorflow.keras.backend as K
    K.clear_session()

    forecast = []
    for i in range(HORIZON):
        future_date = last_date + pd.Timedelta(days=i + 1)
        pred_price  = float(max(pred_prices[i], 0))
        forecast.append({
            "date":            future_date.strftime("%Y-%m-%d"),
            "predicted_price": round(pred_price, 6),
            "low":             round(max(pred_price - std * 0.4, 0), 6),
            "high":            round(pred_price + std * 0.4, 6),
        })

    predicted_day7 = forecast[-1]["predicted_price"]
    growth_pct     = ((predicted_day7 - last_price) / last_price) * 100

    if growth_pct > 5:
        signal, advice = "BUY",   "Strong upward trend predicted — good buy window."
    elif growth_pct > 1:
        signal, advice = "BUY",   "Moderate growth predicted — consider buying."
    elif growth_pct < -5:
        signal, advice = "AVOID", "Significant decline predicted — consider avoiding."
    elif growth_pct < -1:
        signal, advice = "HOLD",  "Slight decline predicted — hold or wait."
    else:
        signal, advice = "HOLD",  "Price expected to remain stable — hold position."

    latest     = df.iloc[-1]
    indicators = {}
    for col in ["rsi", "macd", "fear_greed", "volume"]:
        if col in df.columns:
            indicators[col] = round(float(latest[col]), 4)

    return {
        "coin_id":              coin_id,
        "current_price":        round(last_price, 6),
        "predicted_price_day7": round(predicted_day7, 6),
        "growth_percent":       round(growth_pct, 4),
        "signal":               signal,
        "advice":               advice,
        "indicators":           indicators,
        "features_used":        available,
        "training_rows":        len(df),
        "forecast":             forecast,
    }

def save_predictions(result, conn):
    predicted_at = datetime.now()
    rows = []
    for item in result["forecast"]:
        rows.append((
            result["coin_id"], predicted_at, item["date"],
            result["current_price"], item["predicted_price"],
            item["low"], item["high"],
            result["growth_percent"], result["signal"],
            result["advice"], ",".join(result["features_used"]),
        ))
    sql = """
        INSERT INTO predictions (
            coin_id, predicted_at, predicted_for,
            current_price, predicted_price,
            low_price, high_price,
            growth_percent, signal, advice, features_used
        ) VALUES %s
        ON CONFLICT DO NOTHING
    """
    with conn.cursor() as cur:
        execute_values(cur, sql, rows)
    conn.commit()

def cleanup_old_predictions(conn):
    print(f"\nCleaning up predictions older than {KEEP_PREDICTIONS_DAYS} days...")
    with conn.cursor() as cur:
        cur.execute("""
            DELETE FROM predictions
            WHERE predicted_at < NOW() - INTERVAL '%s days'
        """, (KEEP_PREDICTIONS_DAYS,))
        deleted = cur.rowcount
    conn.commit()
    print(f"  Deleted {deleted} old predictions")
    return deleted

def run_trainer():
    if not DB_URL:
        print("ERROR: DATABASE_URL not set")
        sys.exit(1)

    print(f"\n{'='*60}")
    print(f"Prediction Trainer started at {datetime.now()}")
    print(f"Model: LSTM with K.clear_session() fix")
    print(f"Lookback: {LOOKBACK} days | Horizon: {HORIZON} days | Epochs: 30")
    print(f"Batch: coins {BATCH_START} to {BATCH_END}")
    print(f"{'='*60}\n")

    conn     = get_conn()
    all_coins = get_all_coins(conn)

    # Slice coins for this batch
    coins    = all_coins[BATCH_START:BATCH_END]
    total    = len(all_coins)
    ok, fail = [], []
    results  = []

    print(f"Training LSTM for {len(coins)} coins (batch {BATCH_START}-{BATCH_END} of {total})...\n")

    for i, (coin_id, symbol, name, risk_tier) in enumerate(coins, BATCH_START + 1):
        try:
            print(f"[{i}/{total}] {coin_id}...", end=" ", flush=True)
            df = load_coin_data(coin_id, conn)

            if len(df) < LOOKBACK + HORIZON + 2:
                print(f"SKIP ({len(df)} rows)")
                continue

            result = train_and_predict(coin_id, df)
            if result is None:
                print("SKIP (no sequences)")
                continue

            result["symbol"]    = symbol
            result["name"]      = name
            result["risk_tier"] = risk_tier
            save_predictions(result, conn)
            results.append(result)
            ok.append(coin_id)
            print(f"OK | {result['signal']:<5} | {result['growth_percent']:+.2f}% | {result['training_rows']} rows")

        except Exception as e:
            print(f"FAILED: {e}")
            fail.append(coin_id)

    # Only cleanup on last batch
    deleted = 0
    if BATCH_END >= total:
        deleted = cleanup_old_predictions(conn)

    conn.close()

    print(f"\n{'='*60}")
    print(f"Training complete at {datetime.now()}")
    print(f"Batch:    coins {BATCH_START}-{min(BATCH_END, total)} of {total}")
    print(f"Success:  {len(ok)} coins")
    print(f"Failed:   {len(fail)} coins")
    if deleted:
        print(f"Cleaned:  {deleted} old predictions")

    if results:
        print(f"\nTop 5 by predicted growth:")
        print(f"{'symbol':<8} {'coin_id':<25} {'growth%':<10} {'signal'}")
        print("-" * 55)
        for r in sorted(results, key=lambda x: x["growth_percent"], reverse=True)[:5]:
            print(f"{r['symbol']:<8} {r['coin_id']:<25} {r['growth_percent']:+.2f}%     {r['signal']}")
    print(f"{'='*60}")

if __name__ == "__main__":
    run_trainer()

import os
import sys
import time
import requests
import pandas as pd
import numpy as np
import psycopg2
from psycopg2.extras import execute_values
from datetime import datetime, date, timedelta

DB_URL        = os.environ.get("DATABASE_URL", "")
BINANCE_URL   = "https://api.binance.com/api/v3/klines"
BINANCE_INFO  = "https://api.binance.com/api/v3/exchangeInfo"
COINGECKO_URL = "https://api.coingecko.com/api/v3"
FEARGREED_URL = "https://api.alternative.me/fng/?limit=365"
COINGECKO_KEY = os.environ.get("COINGECKO_API_KEY", "")

TOP_N_COINS       = 100
CANDLES_PER_PAGE  = 1000   # Binance max per request
MAX_PAGES         = 5      # 5 x 1000 = 5000 days max
DELETE_OLDER_THAN = 5      # delete rows older than 5 years

def get_conn():
    return psycopg2.connect(DB_URL, sslmode="require")

# ── Technical Indicators ──────────────────────────────────────────────────────
def calc_rsi(prices, period=14):
    delta = prices.diff()
    gain  = delta.clip(lower=0).rolling(period).mean()
    loss  = (-delta.clip(upper=0)).rolling(period).mean()
    return 100 - (100 / (1 + gain / loss))

def calc_macd(prices, fast=12, slow=26, signal=9):
    ema_fast = prices.ewm(span=fast, adjust=False).mean()
    ema_slow = prices.ewm(span=slow, adjust=False).mean()
    macd     = ema_fast - ema_slow
    sig      = macd.ewm(span=signal, adjust=False).mean()
    return macd, sig, macd - sig

def calc_bollinger(prices, period=20, std_dev=2):
    sma = prices.rolling(period).mean()
    std = prices.rolling(period).std()
    return sma + std_dev * std, sma, sma - std_dev * std

# ── Get top 100 coins from CoinGecko ─────────────────────────────────────────
def get_top_coins(n=TOP_N_COINS):
    print(f"Fetching top {n} coins from CoinGecko...")
    headers = {"x-cg-demo-api-key": COINGECKO_KEY} if COINGECKO_KEY else {}
    coins   = []
    page    = 1

    while len(coins) < n:
        try:
            r = requests.get(
                f"{COINGECKO_URL}/coins/markets",
                params={
                    "vs_currency": "usd",
                    "order":       "market_cap_desc",
                    "per_page":    50,
                    "page":        page,
                    "sparkline":   False,
                },
                headers=headers,
                timeout=10
            )
            r.raise_for_status()
            data = r.json()
            if not data:
                break
            for coin in data:
                coins.append({
                    "coin_id":    coin["id"],
                    "symbol":     coin["symbol"].upper(),
                    "name":       coin["name"],
                    "market_cap": coin.get("market_cap", 0),
                })
            page += 1
            time.sleep(2)
        except Exception as e:
            print(f"  CoinGecko error page {page}: {e}")
            break

    print(f"  Got {len(coins[:n])} coins")
    return coins[:n]

# ── Get all Binance USDT pairs ────────────────────────────────────────────────
def get_binance_symbols():
    print("Fetching Binance USDT pairs...")
    try:
        r = requests.get(BINANCE_INFO, timeout=15)
        r.raise_for_status()
        symbols = set()
        for s in r.json()["symbols"]:
            if (s["quoteAsset"] == "USDT" and
                s["status"] == "TRADING" and
                s["isSpotTradingAllowed"]):
                symbols.add(s["baseAsset"].upper())
        print(f"  Found {len(symbols)} USDT pairs on Binance")
        return symbols
    except Exception as e:
        print(f"  Binance info error: {e}")
        return set()

# ── Match top 100 with Binance ────────────────────────────────────────────────
def match_coins(top_coins, binance_symbols):
    matched, skipped = [], []
    for coin in top_coins:
        symbol = coin["symbol"].upper()
        if symbol in binance_symbols:
            coin["binance_pair"] = f"{symbol}USDT"
            matched.append(coin)
        else:
            skipped.append(coin["symbol"])
    print(f"  Matched: {len(matched)} coins have Binance pairs")
    if skipped:
        print(f"  Skipped: {', '.join(skipped)}")
    return matched

# ── Assign risk tier by market cap ───────────────────────────────────────────
def assign_risk(coin):
    mc = coin.get("market_cap", 0)
    if mc >= 50_000_000_000:  return "low"
    if mc >= 5_000_000_000:   return "medium"
    return "high"

# ── Upsert coins into DB ──────────────────────────────────────────────────────
def upsert_coins(coins, conn):
    rows = [(c["coin_id"], c["symbol"], c["name"], assign_risk(c)) for c in coins]
    sql  = """
        INSERT INTO coins (coin_id, symbol, name, risk_tier)
        VALUES %s
        ON CONFLICT (coin_id) DO UPDATE SET
            symbol    = EXCLUDED.symbol,
            name      = EXCLUDED.name,
            is_active = TRUE
    """
    with conn.cursor() as cur:
        execute_values(cur, sql, rows)
    conn.commit()
    print(f"  Upserted {len(rows)} coins into DB")

# ── Fetch paginated OHLCV from Binance ────────────────────────────────────────
def fetch_binance_paginated(pair, full_history=False):
    all_data = []

    if not full_history:
        # Daily update — just last 7 days
        r = requests.get(BINANCE_URL, params={
            "symbol": pair, "interval": "1d", "limit": 7
        }, timeout=10)
        r.raise_for_status()
        all_data = r.json()
    else:
        # Full history — paginate backwards up to MAX_PAGES
        end_time = None
        for page in range(MAX_PAGES):
            params = {
                "symbol":   pair,
                "interval": "1d",
                "limit":    CANDLES_PER_PAGE,
            }
            if end_time:
                params["endTime"] = end_time

            r = requests.get(BINANCE_URL, params=params, timeout=10)
            r.raise_for_status()
            page_data = r.json()

            if not page_data:
                break

            all_data = page_data + all_data  # prepend older data
            end_time = page_data[0][0] - 1   # go further back in time

            if len(page_data) < CANDLES_PER_PAGE:
                break  # reached the beginning of coin history

            time.sleep(0.1)

    if not all_data:
        return pd.DataFrame()

    df = pd.DataFrame(all_data, columns=[
        "open_time","open","high","low","close","volume",
        "close_time","quote_volume","trades",
        "taker_buy_base","taker_buy_quote","ignore"
    ])
    df["date"]         = pd.to_datetime(df["open_time"], unit="ms").dt.date
    df["open"]         = df["open"].astype(float)
    df["high"]         = df["high"].astype(float)
    df["low"]          = df["low"].astype(float)
    df["close"]        = df["close"].astype(float)
    df["volume"]       = df["volume"].astype(float)
    df["quote_volume"] = df["quote_volume"].astype(float)
    df["trades"]       = df["trades"].astype(int)

    # Remove duplicates from pagination overlap
    df = df.drop_duplicates(subset=["date"]).sort_values("date").reset_index(drop=True)
    return df[["date","open","high","low","close","volume","quote_volume","trades"]]

# ── Fetch fear/greed ──────────────────────────────────────────────────────────
def fetch_fear_greed():
    try:
        r      = requests.get(FEARGREED_URL, timeout=10)
        r.raise_for_status()
        data   = r.json().get("data", [])
        result = {}
        for entry in data:
            d         = pd.to_datetime(int(entry["timestamp"]), unit="s").date()
            result[d] = int(entry["value"])
        print(f"  Fear/Greed: {len(result)} days fetched")
        return result
    except Exception as e:
        print(f"  Fear/Greed error: {e}")
        return {}

# ── Transform — calculate all indicators ─────────────────────────────────────
def transform(df, fear_greed):
    df = df.copy().sort_values("date").reset_index(drop=True)
    df["fear_greed"]       = df["date"].map(fear_greed).fillna(50).astype(int)
    df["rsi"]              = calc_rsi(df["close"])
    df["macd"], df["macd_signal"], df["macd_hist"] = calc_macd(df["close"])
    df["bb_upper"], df["bb_mid"], df["bb_lower"]   = calc_bollinger(df["close"])
    df["price_change_1d"]  = df["close"].pct_change(1)
    df["price_change_7d"]  = df["close"].pct_change(7)
    df["volume_change_1d"] = df["volume"].pct_change(1)
    df["hl_range"]         = (df["high"] - df["low"]) / df["close"]
    return df.dropna().reset_index(drop=True)

# ── Load into DB ──────────────────────────────────────────────────────────────
def load_to_db(df, coin_id, conn):
    rows = []
    for _, row in df.iterrows():
        rows.append((
            coin_id,
            row["date"],
            row["open"],   row["high"],   row["low"],   row["close"],
            row["volume"], row["quote_volume"], int(row["trades"]),
            None,
            int(row["fear_greed"]),
            row["rsi"],    row["macd"],   row["macd_signal"], row["macd_hist"],
            row["bb_upper"], row["bb_mid"], row["bb_lower"],
            row["price_change_1d"], row["price_change_7d"],
            row["volume_change_1d"], row["hl_range"],
        ))

    sql = """
        INSERT INTO ohlcv_data (
            coin_id, date, open, high, low, close,
            volume, quote_volume, trades,
            market_cap, fear_greed,
            rsi, macd, macd_signal, macd_hist,
            bb_upper, bb_mid, bb_lower,
            price_change_1d, price_change_7d,
            volume_change_1d, hl_range
        ) VALUES %s
        ON CONFLICT (coin_id, date) DO UPDATE SET
            close            = EXCLUDED.close,
            volume           = EXCLUDED.volume,
            fear_greed       = EXCLUDED.fear_greed,
            rsi              = EXCLUDED.rsi,
            macd             = EXCLUDED.macd,
            macd_hist        = EXCLUDED.macd_hist,
            bb_upper         = EXCLUDED.bb_upper,
            bb_lower         = EXCLUDED.bb_lower,
            price_change_1d  = EXCLUDED.price_change_1d,
            price_change_7d  = EXCLUDED.price_change_7d
    """
    with conn.cursor() as cur:
        execute_values(cur, sql, rows)
    conn.commit()
    return len(rows)

# ── Delete data older than 5 years ───────────────────────────────────────────
def cleanup_old_data(conn):
    cutoff = date.today() - timedelta(days=365 * DELETE_OLDER_THAN)
    print(f"\nCleaning up data older than {cutoff} ({DELETE_OLDER_THAN} years)...")
    with conn.cursor() as cur:
        cur.execute("DELETE FROM ohlcv_data WHERE date < %s", (cutoff,))
        deleted = cur.rowcount
    conn.commit()
    if deleted > 0:
        print(f"  Deleted {deleted} old rows")
    else:
        print(f"  No old data to delete")
    return deleted

# ── Main ETL ──────────────────────────────────────────────────────────────────
def run_etl(full_history=False):
    if not DB_URL:
        print("ERROR: DATABASE_URL not set")
        sys.exit(1)

    print(f"\n{'='*60}")
    print(f"ETL started at {datetime.now()}")
    print(f"Mode: {'FULL HISTORY (paginated max)' if full_history else 'DAILY UPDATE (7 days)'}")
    print(f"Coins: top {TOP_N_COINS} by market cap")
    print(f"{'='*60}")

    conn       = get_conn()
    rows_total = 0
    ok, fail   = [], []

    # Get top 100 and match with Binance
    top_coins       = get_top_coins(TOP_N_COINS)
    binance_symbols = get_binance_symbols()
    matched_coins   = match_coins(top_coins, binance_symbols)

    # Save coins to DB
    print("\nUpserting coins into database...")
    upsert_coins(matched_coins, conn)

    # Fear/greed shared across all coins
    print("\nFetching Fear/Greed index...")
    fear_greed = fetch_fear_greed()

    # Process each coin
    print(f"\nFetching OHLCV for {len(matched_coins)} coins...")
    total = len(matched_coins)

    for i, coin in enumerate(matched_coins, 1):
        coin_id = coin["coin_id"]
        pair    = coin["binance_pair"]

        try:
            print(f"  [{i}/{total}] {coin_id} ({pair})...", end=" ")
            df_raw = fetch_binance_paginated(pair, full_history)

            if df_raw.empty:
                print("NO DATA")
                fail.append(coin_id)
                continue

            df   = transform(df_raw, fear_greed)
            rows = load_to_db(df, coin_id, conn)
            rows_total += rows
            ok.append(coin_id)
            print(f"{rows} rows | {df['date'].min()} → {df['date'].max()}")
            time.sleep(0.2)

        except Exception as e:
            print(f"FAILED: {e}")
            fail.append(coin_id)

    # Cleanup old data (older than 5 years)
    deleted = cleanup_old_data(conn)

    # Log this run
    with conn.cursor() as cur:
        cur.execute("""
            INSERT INTO etl_log (coins_ok, coins_fail, rows_added, status)
            VALUES (%s, %s, %s, %s)
        """, (len(ok), len(fail), rows_total, "success" if not fail else "partial"))
    conn.commit()
    conn.close()

    print(f"\n{'='*60}")
    print(f"ETL complete at {datetime.now()}")
    print(f"Coins success: {len(ok)}")
    print(f"Coins failed:  {len(fail)}")
    print(f"Rows upserted: {rows_total}")
    print(f"Rows deleted:  {deleted} (older than 5 years)")
    print(f"{'='*60}")

    return ok, fail, rows_total


if __name__ == "__main__":
    # python etl_pipeline.py full   → first run, fetch max history
    # python etl_pipeline.py        → daily update, last 7 days only
    full = len(sys.argv) > 1 and sys.argv[1] == "full"
    run_etl(full_history=full)

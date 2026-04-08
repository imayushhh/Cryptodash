import requests
import pandas as pd
import numpy as np
import time
import os
import sys

# ── API Sources ───────────────────────────────────────────────────────────────
BINANCE_URL    = "https://api.binance.com/api/v3/klines"
COINGECKO_URL  = "https://api.coingecko.com/api/v3"
FEARGREED_URL  = "https://api.alternative.me/fng/?limit=60"
COINGECKO_KEY  = os.environ.get("COINGECKO_API_KEY", "")

# ── Coin Mappings ─────────────────────────────────────────────────────────────
COINS = {
    "bitcoin":     {"binance": "BTCUSDT",  "coingecko": "bitcoin"},
    "ethereum":    {"binance": "ETHUSDT",  "coingecko": "ethereum"},
    "solana":      {"binance": "SOLUSDT",  "coingecko": "solana"},
    "binancecoin": {"binance": "BNBUSDT",  "coingecko": "binancecoin"},
    "ripple":      {"binance": "XRPUSDT",  "coingecko": "ripple"},
    "cardano":     {"binance": "ADAUSDT",  "coingecko": "cardano"},
    "dogecoin":    {"binance": "DOGEUSDT", "coingecko": "dogecoin"},
    "tron":        {"binance": "TRXUSDT",  "coingecko": "tron"},
    "avalanche-2": {"binance": "AVAXUSDT", "coingecko": "avalanche-2"},
    "chainlink":   {"binance": "LINKUSDT", "coingecko": "chainlink"},
}

DAYS = 60  # fetch 60 days for enough LSTM training data


# ── Technical Indicators ──────────────────────────────────────────────────────
def calculate_rsi(prices, period=14):
    delta    = prices.diff()
    gain     = delta.clip(lower=0)
    loss     = -delta.clip(upper=0)
    avg_gain = gain.rolling(window=period).mean()
    avg_loss = loss.rolling(window=period).mean()
    rs       = avg_gain / avg_loss
    return 100 - (100 / (1 + rs))


def calculate_macd(prices, fast=12, slow=26, signal=9):
    ema_fast    = prices.ewm(span=fast, adjust=False).mean()
    ema_slow    = prices.ewm(span=slow, adjust=False).mean()
    macd        = ema_fast - ema_slow
    macd_signal = macd.ewm(span=signal, adjust=False).mean()
    macd_hist   = macd - macd_signal
    return macd, macd_signal, macd_hist


def calculate_bollinger(prices, period=20, std_dev=2):
    sma   = prices.rolling(window=period).mean()
    std   = prices.rolling(window=period).std()
    upper = sma + std_dev * std
    lower = sma - std_dev * std
    return upper, sma, lower


# ── Source 1: Binance (OHLCV + Volume) ───────────────────────────────────────
def fetch_binance(coin_id):
    symbol = COINS[coin_id]["binance"]
    try:
        r = requests.get(BINANCE_URL, params={
            "symbol": symbol, "interval": "1d", "limit": DAYS
        }, timeout=10)
        r.raise_for_status()
        data = r.json()

        df = pd.DataFrame(data, columns=[
            "open_time", "open", "high", "low", "close", "volume",
            "close_time", "quote_volume", "trades",
            "taker_buy_base", "taker_buy_quote", "ignore"
        ])
        df["timestamp"]    = pd.to_datetime(df["open_time"], unit="ms").dt.date
        df["price"]        = df["close"].astype(float)
        df["open"]         = df["open"].astype(float)
        df["high"]         = df["high"].astype(float)
        df["low"]          = df["low"].astype(float)
        df["volume"]       = df["volume"].astype(float)
        df["quote_volume"] = df["quote_volume"].astype(float)
        df["trades"]       = df["trades"].astype(int)

        print(f"    Binance: {len(df)} days of OHLCV")
        return df[["timestamp", "price", "open", "high", "low", "volume", "quote_volume", "trades"]]

    except Exception as e:
        print(f"    Binance ERROR: {e}")
        return None


# ── Source 2: CoinGecko (Market Cap) ─────────────────────────────────────────
def fetch_coingecko(coin_id):
    cg_id   = COINS[coin_id]["coingecko"]
    headers = {"x-cg-demo-api-key": COINGECKO_KEY} if COINGECKO_KEY else {}
    try:
        r = requests.get(
            f"{COINGECKO_URL}/coins/{cg_id}/market_chart",
            params={"vs_currency": "usd", "days": str(DAYS)},
            headers=headers,
            timeout=10
        )
        r.raise_for_status()
        data = r.json()

        market_caps = data.get("market_caps", [])
        df = pd.DataFrame(market_caps, columns=["ts", "market_cap"])
        df["timestamp"]  = pd.to_datetime(df["ts"], unit="ms").dt.date
        df["market_cap"] = df["market_cap"].astype(float)

        # Keep one row per day
        df = df.groupby("timestamp", as_index=False).last()

        print(f"    CoinGecko: {len(df)} days of market_cap")
        return df[["timestamp", "market_cap"]]

    except Exception as e:
        print(f"    CoinGecko ERROR: {e}")
        return None


# ── Source 3: Alternative.me (Fear & Greed Index) ────────────────────────────
def fetch_fear_greed():
    try:
        r = requests.get(FEARGREED_URL, timeout=10)
        r.raise_for_status()
        data = r.json().get("data", [])

        rows = []
        for entry in data:
            rows.append({
                "timestamp":        pd.to_datetime(int(entry["timestamp"]), unit="s").date(),
                "fear_greed_value": int(entry["value"]),
                "fear_greed_label": entry["value_classification"],
            })

        df = pd.DataFrame(rows)
        print(f"    Alternative.me: {len(df)} days of fear/greed")
        return df

    except Exception as e:
        print(f"    Alternative.me ERROR: {e}")
        return None


# ── Main Collector ────────────────────────────────────────────────────────────
def fetch_coin_data(coin_id):
    print(f"\n  Fetching {coin_id}...")

    # 1. Binance OHLCV (required)
    df_binance = fetch_binance(coin_id)
    if df_binance is None:
        print(f"  SKIP {coin_id} - Binance failed")
        return False

    # 2. CoinGecko market cap (optional)
    df_cg = fetch_coingecko(coin_id)
    time.sleep(2)  # respect CoinGecko rate limit

    # 3. Fear & Greed (shared across all coins)
    df_fg = fetch_fear_greed()

    # Merge all sources on timestamp
    df = df_binance.copy()
    df["timestamp"] = pd.to_datetime(df["timestamp"])

    if df_cg is not None:
        df_cg["timestamp"] = pd.to_datetime(df_cg["timestamp"])
        df = pd.merge(df, df_cg, on="timestamp", how="left")
    else:
        df["market_cap"] = np.nan

    if df_fg is not None:
        df_fg["timestamp"] = pd.to_datetime(df_fg["timestamp"])
        df = pd.merge(df, df_fg, on="timestamp", how="left")
    else:
        df["fear_greed_value"] = 50
        df["fear_greed_label"] = "Neutral"

    # Fill missing market_cap with forward fill
    df["market_cap"] = df["market_cap"].ffill()
    df["fear_greed_value"] = df["fear_greed_value"].ffill().fillna(50)

    # Calculate technical indicators from Binance price data
    df["rsi"]                          = calculate_rsi(df["price"])
    df["macd"], df["macd_signal"], df["macd_hist"] = calculate_macd(df["price"])
    df["bb_upper"], df["bb_mid"], df["bb_lower"]   = calculate_bollinger(df["price"])

    # Price momentum
    df["price_change_1d"]  = df["price"].pct_change(1)
    df["price_change_7d"]  = df["price"].pct_change(7)
    df["volume_change_1d"] = df["volume"].pct_change(1)

    # High-low range (volatility indicator)
    df["hl_range"] = (df["high"] - df["low"]) / df["price"]

    # Drop NaN rows from indicator warmup period
    df = df.dropna().reset_index(drop=True)

    # Save to CSV
    output_file = f"market_data_{coin_id}.csv"
    df.to_csv(output_file, index=False)

    print(f"  SAVED {coin_id}: {len(df)} days, {len(df.columns)} features -> {output_file}")
    print(f"  Features: {list(df.columns)}")
    return True


def collect_all(coins=None):
    target = coins if coins else list(COINS.keys())
    print(f"\nCollecting data for {len(target)} coins from 3 sources...")
    print("Sources: Binance (OHLCV) + CoinGecko (market cap) + Alternative.me (fear/greed)\n")

    ok, fail = [], []
    for c in target:
        if fetch_coin_data(c):
            ok.append(c)
        else:
            fail.append(c)

    print(f"\n{'='*50}")
    print(f"Done. Success: {len(ok)}, Failed: {len(fail)}")
    if fail:
        print(f"Failed: {', '.join(fail)}")
    print(f"{'='*50}")
    return ok, fail


if __name__ == "__main__":
    coins_arg = sys.argv[1:] if len(sys.argv) > 1 else None
    collect_all(coins_arg)
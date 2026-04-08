"""
CryptoDash AI Server - Lightweight version for Render
Reads predictions from Aiven DB — no training on server
RAM usage: ~50MB (no TensorFlow needed)
"""
from flask import Flask, jsonify, request
from flask_cors import CORS
import psycopg2
import psycopg2.extras
import os
import json
from datetime import datetime, date, timedelta

app  = Flask(__name__)
CORS(app)

DB_URL = os.environ.get("DATABASE_URL", "")

COIN_RISK_TIERS = {}
COIN_SYMBOLS    = {}
COIN_NAMES      = {}

def get_conn():
    return psycopg2.connect(DB_URL, sslmode="require")

def load_coin_metadata():
    global COIN_RISK_TIERS, COIN_SYMBOLS, COIN_NAMES
    if not DB_URL:
        return
    try:
        conn = get_conn()
        with conn.cursor() as cur:
            cur.execute("SELECT coin_id, symbol, name, risk_tier FROM coins WHERE is_active = TRUE")
            for coin_id, symbol, name, risk_tier in cur.fetchall():
                COIN_RISK_TIERS[coin_id] = risk_tier
                COIN_SYMBOLS[coin_id]    = symbol
                COIN_NAMES[coin_id]      = name
        conn.close()
        print(f"Loaded metadata for {len(COIN_SYMBOLS)} coins")
    except Exception as e:
        print(f"Warning: Could not load coin metadata: {e}")

def get_latest_prediction(coin_id, conn):
    """Get the most recent prediction for a coin"""
    sql = """
        SELECT DISTINCT ON (predicted_for)
            coin_id, predicted_at, predicted_for,
            current_price, predicted_price,
            low_price, high_price,
            growth_percent, signal, advice
        FROM predictions
        WHERE coin_id = %s
          AND predicted_at >= NOW() - INTERVAL '2 days'
        ORDER BY predicted_for ASC, predicted_at DESC
    """
    with conn.cursor(cursor_factory=psycopg2.extras.RealDictCursor) as cur:
        cur.execute(sql, (coin_id,))
        rows = cur.fetchall()
    return rows

def get_latest_indicators(coin_id, conn):
    """Get latest RSI, MACD, fear/greed for a coin"""
    sql = """
        SELECT rsi, macd, fear_greed, volume, close
        FROM ohlcv_data
        WHERE coin_id = %s
        ORDER BY date DESC
        LIMIT 1
    """
    with conn.cursor(cursor_factory=psycopg2.extras.RealDictCursor) as cur:
        cur.execute(sql, (coin_id,))
        return cur.fetchone()


@app.route("/health", methods=["GET"])
def health():
    return jsonify({
        "status":  "ok",
        "service": "CryptoDash AI",
        "mode":    "db-read-only",
        "coins":   len(COIN_SYMBOLS)
    })


@app.route("/coins", methods=["GET"])
def list_coins():
    """List all tracked coins"""
    coins = []
    for coin_id, symbol in COIN_SYMBOLS.items():
        coins.append({
            "coin_id":   coin_id,
            "symbol":    symbol,
            "name":      COIN_NAMES.get(coin_id, coin_id),
            "risk_tier": COIN_RISK_TIERS.get(coin_id, "high"),
        })
    return jsonify({"coins": coins, "total": len(coins)})


@app.route("/predict/<coin_id>", methods=["GET"])
def predict_coin(coin_id):
    """Get latest prediction for a coin"""
    if not DB_URL:
        return jsonify({"error": "Database not configured"}), 500

    try:
        conn = get_conn()
        rows = get_latest_prediction(coin_id, conn)

        if not rows:
            return jsonify({
                "error": f"No predictions found for {coin_id}. Trainer may not have run yet."
            }), 404

        indicators = get_latest_indicators(coin_id, conn)
        conn.close()

        # Build forecast list from rows
        forecast = []
        for row in rows:
            forecast.append({
                "date":            str(row["predicted_for"]),
                "predicted_price": float(row["predicted_price"] or 0),
                "low":             float(row["low_price"] or 0),
                "high":            float(row["high_price"] or 0),
            })

        latest     = rows[-1]
        growth_pct = float(latest["growth_percent"] or 0)

        return jsonify({
            "coin_id":              coin_id,
            "symbol":               COIN_SYMBOLS.get(coin_id, coin_id.upper()),
            "name":                 COIN_NAMES.get(coin_id, coin_id),
            "risk_tier":            COIN_RISK_TIERS.get(coin_id, "high"),
            "current_price":        float(latest["current_price"] or 0),
            "predicted_price_day7": float(latest["predicted_price"] or 0),
            "growth_percent":       growth_pct,
            "signal":               latest["signal"],
            "advice":               latest["advice"],
            "predicted_at":         str(latest["predicted_at"]),
            "indicators":           dict(indicators) if indicators else {},
            "forecast":             forecast,
        })

    except Exception as e:
        return jsonify({"error": str(e)}), 500


@app.route("/predict/all", methods=["GET"])
def predict_all():
    """Get predictions for all coins sorted by growth"""
    if not DB_URL:
        return jsonify({"error": "Database not configured"}), 500

    try:
        conn = get_conn()
        sql  = """
            SELECT DISTINCT ON (coin_id)
                coin_id, current_price, predicted_price,
                growth_percent, signal, advice, predicted_at
            FROM predictions
            WHERE predicted_at >= NOW() - INTERVAL '2 days'
            ORDER BY coin_id, predicted_at DESC
        """
        with conn.cursor(cursor_factory=psycopg2.extras.RealDictCursor) as cur:
            cur.execute(sql)
            rows = cur.fetchall()
        conn.close()

        results = []
        for row in rows:
            coin_id = row["coin_id"]
            results.append({
                "coin_id":              coin_id,
                "symbol":               COIN_SYMBOLS.get(coin_id, coin_id.upper()),
                "name":                 COIN_NAMES.get(coin_id, coin_id),
                "risk_tier":            COIN_RISK_TIERS.get(coin_id, "high"),
                "current_price":        float(row["current_price"] or 0),
                "predicted_price_day7": float(row["predicted_price"] or 0),
                "growth_percent":       float(row["growth_percent"] or 0),
                "signal":               row["signal"],
                "advice":               row["advice"],
                "predicted_at":         str(row["predicted_at"]),
            })

        results.sort(key=lambda x: x["growth_percent"], reverse=True)
        return jsonify({"coins": results, "total": len(results)})

    except Exception as e:
        return jsonify({"error": str(e)}), 500


@app.route("/advisor", methods=["POST"])
def advisor():
    """Investment advisor — reads from DB, no training"""
    body      = request.get_json(silent=True) or {}
    budget    = float(body.get("budget", 0))
    risk      = body.get("risk", "medium").lower()
    num_coins = int(body.get("num_coins", 3))

    if budget <= 0:
        return jsonify({"error": "Budget must be greater than 0"}), 400
    if risk not in ("low", "medium", "high"):
        return jsonify({"error": "Risk must be low, medium, or high"}), 400

    try:
        conn = get_conn()
        sql  = """
            SELECT DISTINCT ON (p.coin_id)
                p.coin_id, p.current_price, p.predicted_price,
                p.growth_percent, p.signal, p.advice,
                c.symbol, c.name, c.risk_tier
            FROM predictions p
            JOIN coins c ON p.coin_id = c.coin_id
            WHERE p.predicted_at >= NOW() - INTERVAL '2 days'
              AND c.is_active = TRUE
            ORDER BY p.coin_id, p.predicted_at DESC
        """
        with conn.cursor(cursor_factory=psycopg2.extras.RealDictCursor) as cur:
            cur.execute(sql)
            all_preds = cur.fetchall()
        conn.close()

        # Filter by risk
        def allowed(coin_tier, user_risk):
            if user_risk == "low":    return coin_tier == "low"
            if user_risk == "medium": return coin_tier in ("low", "medium")
            return True

        filtered = [
            p for p in all_preds
            if allowed(p["risk_tier"], risk)
            and float(p["growth_percent"] or 0) > 0
        ]

        # Sort by growth descending
        filtered.sort(key=lambda x: float(x["growth_percent"] or 0), reverse=True)
        selected = filtered[:num_coins]

        if not selected:
            return jsonify({"error": "No suitable coins found for your risk profile"}), 404

        # Weighted budget allocation
        total_growth = sum(max(float(c["growth_percent"]), 0.01) for c in selected)
        portfolio    = []
        total_return = 0.0

        for coin in selected:
            growth_pct      = float(coin["growth_percent"])
            weight          = max(growth_pct, 0.01) / total_growth
            allocated       = round(budget * weight, 2)
            expected_return = round(allocated * (growth_pct / 100), 2)
            total_return   += expected_return

            portfolio.append({
                "coin_id":              coin["coin_id"],
                "name":                 coin["name"],
                "symbol":               coin["symbol"],
                "risk_tier":            coin["risk_tier"],
                "current_price":        float(coin["current_price"] or 0),
                "predicted_price_day7": float(coin["predicted_price"] or 0),
                "growth_percent":       growth_pct,
                "signal":               coin["signal"],
                "advice":               coin["advice"],
                "invest_amount":        allocated,
                "expected_return":      expected_return,
            })

        return jsonify({
            "budget":                budget,
            "risk":                  risk,
            "num_coins":             len(portfolio),
            "total_expected_return": round(total_return, 2),
            "expected_return_pct":   round((total_return / budget) * 100, 4),
            "portfolio":             portfolio,
        })

    except Exception as e:
        return jsonify({"error": str(e)}), 500


if __name__ == "__main__":
    load_coin_metadata()
    port = int(os.environ.get("PORT", 5001))
    print(f"\nCryptoDash AI server starting on port {port}")
    print(f"Mode: DB read-only (no TensorFlow)")
    print(f"Endpoints: /health /coins /predict/<coin> /predict/all /advisor\n")
    app.run(host="0.0.0.0", port=port, debug=False)

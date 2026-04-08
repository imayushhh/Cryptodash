"""
CryptoDash AI Prediction Server
Runs on port 5001 — Spring Boot calls this via HTTP.
"""

from flask import Flask, jsonify, request
from flask_cors import CORS
import os
import sys

# Add current directory to path so ai_model can be imported
sys.path.insert(0, os.path.dirname(__file__))
from ai_model import predict

app = Flask(__name__)
CORS(app)  # Allow Spring Boot (port 8080) to call this

# Coins your system supports — matches CryptoService.java demo data + CoinGecko ids
SUPPORTED_COINS = [
    "bitcoin",
    "ethereum",
    "solana",
    "binancecoin",
    "ripple",
    "cardano",
    "dogecoin",
    "tron",
    "avalanche-2",
    "chainlink",
]

# Risk tier per coin (used by advisor)
COIN_RISK_TIERS = {
    "bitcoin":      "low",
    "ethereum":     "low",
    "binancecoin":  "medium",
    "ripple":       "medium",
    "solana":       "medium",
    "cardano":      "high",
    "dogecoin":     "high",
    "tron":         "high",
    "avalanche-2":  "high",
    "chainlink":    "high",
}

COIN_SYMBOLS = {
    "bitcoin":      "BTC",
    "ethereum":     "ETH",
    "solana":       "SOL",
    "binancecoin":  "BNB",
    "ripple":       "XRP",
    "cardano":      "ADA",
    "dogecoin":     "DOGE",
    "tron":         "TRX",
    "avalanche-2":  "AVAX",
    "chainlink":    "LINK",
}

COIN_NAMES = {
    "bitcoin":      "Bitcoin",
    "ethereum":     "Ethereum",
    "solana":       "Solana",
    "binancecoin":  "BNB",
    "ripple":       "XRP",
    "cardano":      "Cardano",
    "dogecoin":     "Dogecoin",
    "tron":         "TRON",
    "avalanche-2":  "Avalanche",
    "chainlink":    "Chainlink",
}


@app.route("/health", methods=["GET"])
def health():
    return jsonify({"status": "ok", "service": "CryptoDash AI"})


@app.route("/predict/<coin_id>", methods=["GET"])
def predict_coin(coin_id):
    """
    GET /predict/bitcoin
    GET /predict/ethereum?horizon=7
    Returns 7-day price forecast + signal
    """
    if coin_id not in SUPPORTED_COINS:
        return jsonify({"error": f"Coin '{coin_id}' not supported. Supported: {SUPPORTED_COINS}"}), 400

    horizon = int(request.args.get("horizon", 7))
    result = predict(coin_id, horizon)

    if "error" in result:
        return jsonify(result), 500

    # Attach metadata
    result["symbol"]    = COIN_SYMBOLS.get(coin_id, coin_id.upper())
    result["name"]      = COIN_NAMES.get(coin_id, coin_id)
    result["risk_tier"] = COIN_RISK_TIERS.get(coin_id, "high")

    return jsonify(result)


@app.route("/predict/all", methods=["GET"])
def predict_all():
    """
    GET /predict/all
    Returns predictions for all supported coins, sorted by growth % descending.
    Used by the investment advisor.
    """
    results = []
    errors = []

    for coin_id in SUPPORTED_COINS:
        result = predict(coin_id, 7)
        if "error" in result:
            errors.append({"coin_id": coin_id, "error": result["error"]})
            continue

        result["symbol"]    = COIN_SYMBOLS.get(coin_id, coin_id.upper())
        result["name"]      = COIN_NAMES.get(coin_id, coin_id)
        result["risk_tier"] = COIN_RISK_TIERS.get(coin_id, "high")
        results.append(result)

    # Sort by growth % descending — best predicted return first
    results.sort(key=lambda x: x.get("growth_percent", 0), reverse=True)

    return jsonify({
        "coins": results,
        "errors": errors,
        "total": len(results)
    })


@app.route("/advisor", methods=["POST"])
def advisor():
    """
    POST /advisor
    Body: { "budget": 1000, "risk": "medium", "num_coins": 3 }
    Returns full portfolio breakdown with allocated amounts and expected returns.
    """
    body = request.get_json(silent=True) or {}
    budget    = float(body.get("budget", 0))
    risk      = body.get("risk", "medium").lower()
    num_coins = int(body.get("num_coins", 3))

    if budget <= 0:
        return jsonify({"error": "Budget must be greater than 0."}), 400
    if risk not in ("low", "medium", "high"):
        return jsonify({"error": "Risk must be low, medium, or high."}), 400
    if num_coins < 1 or num_coins > len(SUPPORTED_COINS):
        return jsonify({"error": f"num_coins must be between 1 and {len(SUPPORTED_COINS)}."}), 400

    # Step 1: Get all predictions
    all_preds = []
    for coin_id in SUPPORTED_COINS:
        result = predict(coin_id, 7)
        if "error" in result:
            continue
        result["symbol"]    = COIN_SYMBOLS.get(coin_id, coin_id.upper())
        result["name"]      = COIN_NAMES.get(coin_id, coin_id)
        result["risk_tier"] = COIN_RISK_TIERS.get(coin_id, "high")
        all_preds.append(result)

    # Step 2: Filter by risk tolerance
    def allowed_by_risk(coin_tier, user_risk):
        if user_risk == "low":
            return coin_tier == "low"
        if user_risk == "medium":
            return coin_tier in ("low", "medium")
        return True  # high risk = all coins

    filtered = [c for c in all_preds if allowed_by_risk(c["risk_tier"], risk)]

    # Step 3: Keep only positive growth predictions, sort high to low
    positive = [c for c in filtered if c.get("growth_percent", 0) > 0]
    positive.sort(key=lambda x: x["growth_percent"], reverse=True)

    # Fallback: if no positive coins, take least negative
    if not positive:
        positive = sorted(filtered, key=lambda x: x.get("growth_percent", 0), reverse=True)

    selected = positive[:num_coins]

    if not selected:
        return jsonify({"error": "No suitable coins found for your risk profile."}), 404

    # Step 4: Weighted budget allocation by predicted growth %
    total_growth = sum(max(c["growth_percent"], 0.01) for c in selected)
    portfolio = []
    total_expected_return = 0.0

    for coin in selected:
        weight          = max(coin["growth_percent"], 0.01) / total_growth
        allocated       = round(budget * weight, 2)
        expected_return = round(allocated * (coin["growth_percent"] / 100), 2)
        total_expected_return += expected_return

        portfolio.append({
            "coin_id":              coin["coin_id"],
            "name":                 coin["name"],
            "symbol":               coin["symbol"],
            "risk_tier":            coin["risk_tier"],
            "current_price":        coin["current_price"],
            "predicted_price_day7": coin["predicted_price_day7"],
            "growth_percent":       coin["growth_percent"],
            "signal":               coin["signal"],
            "advice":               coin["advice"],
            "invest_amount":        allocated,
            "expected_return":      expected_return,
            "forecast":             coin["forecast"],
        })

    return jsonify({
        "budget":                budget,
        "risk":                  risk,
        "num_coins":             len(portfolio),
        "total_expected_return": round(total_expected_return, 2),
        "expected_return_pct":   round((total_expected_return / budget) * 100, 4),
        "portfolio":             portfolio,
    })


if __name__ == "__main__":
    port = int(os.environ.get("AI_PORT", 5001))
    print(f"\nCryptoDash AI server starting on port {port}...")
    print("Endpoints:")
    print(f"  GET  http://localhost:{port}/health")
    print(f"  GET  http://localhost:{port}/predict/<coin_id>")
    print(f"  GET  http://localhost:{port}/predict/all")
    print(f"  POST http://localhost:{port}/advisor")
    print("\nMake sure you ran data_collector.py first!\n")
    app.run(host="0.0.0.0", port=port, debug=False)

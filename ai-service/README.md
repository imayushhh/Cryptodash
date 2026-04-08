# CryptoDash AI Service

A Python Flask server that provides 7-day price predictions and investment advice.
Spring Boot calls this service at http://localhost:5001

---

## Setup (run once)

Open a terminal inside this `ai-service/` folder.

### 1. Create virtual environment
```
python -m venv venv
```

### 2. Activate it
Windows:
```
venv\Scripts\activate
```
Mac/Linux:
```
source venv/bin/activate
```

### 3. Install dependencies
```
pip install -r requirements.txt
```

---

## Run (every time you start the project)

### Step 1 — Collect price data (run once a day is enough)
```
python data_collector.py
```
This creates market_data_bitcoin.csv, market_data_ethereum.csv etc.

### Step 2 — Start the prediction server
```
python server.py
```
Server runs on http://localhost:5001

---

## Test it

```
curl http://localhost:5001/health
curl http://localhost:5001/predict/bitcoin
curl http://localhost:5001/predict/all
curl -X POST http://localhost:5001/advisor \
  -H "Content-Type: application/json" \
  -d "{\"budget\": 1000, \"risk\": \"medium\", \"num_coins\": 3}"
```

---

## Endpoints

| Method | URL | Description |
|--------|-----|-------------|
| GET | /health | Health check |
| GET | /predict/{coin_id} | 7-day forecast for one coin |
| GET | /predict/all | All coins ranked by predicted growth |
| POST | /advisor | Full portfolio breakdown |

## Advisor request body
```json
{
  "budget": 1000,
  "risk": "low|medium|high",
  "num_coins": 3
}
```

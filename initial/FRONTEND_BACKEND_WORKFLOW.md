# CryptoDash Frontend & Backend Workflow

This document explains how the **frontend** (HTML + JS) and the **backend** (Spring Boot) work together in the `initial` project of CryptoDash.

It focuses on:
- Where data comes from (external APIs)
- Which backend endpoints expose that data
- How each page (Top/Trending, Converter, News) fetches and displays it

---

## 1. High‑Level Architecture

- **Backend**: Spring Boot 3 app in the `initial` module
  - Main class: `com.example.SpringBootInitialApplication`
  - REST controller: `com.example.crypto.CryptoController`
  - Service: `com.example.crypto.CryptoService`
  - DTOs / models:
    - `CryptoCurrency` – individual coin data for tables/cards/converter
    - `GlobalMarketData` – overall crypto market stats
    - `FearGreedData` – fear & greed index
- **Frontend**: Static HTML + JS served by Spring Boot
  - `src/main/resources/static/index.html` – main dashboard (Top / Trending + 4 metric cards)
  - `src/main/resources/static/currency.html` – currency/coin converter
  - `src/main/resources/static/news.html` – news page (reuses common metrics where present)
  - Shared script: `src/main/resources/static/global-metrics.js`

---

## 2. External APIs Used

The backend never exposes your external API keys directly to the browser. Instead, it calls these APIs server‑side:

1. **CoinGecko – coins/markets**
   - `GET https://api.coingecko.com/api/v3/coins/markets`
   - Query params:
     - `vs_currency={fiat}` (e.g. `usd`, `eur`, `inr`)
     - `order=market_cap_desc`
     - `price_change_percentage=24h`
   - Header:
     - `x-cg-demo-api-key: {coingecko.api.key from application.properties}`
   - Used for:
     - Top / Trending tables
     - Avg Price Change card
     - Avg Market Cap Change card
     - Converter page

2. **CoinGecko – global market data**
   - `GET https://api.coingecko.com/api/v3/global`
   - Used for:
     - Market Cap metric card (total global crypto market cap & 24h change)

3. **Alternative.me – Fear & Greed index**
   - `GET https://api.alternative.me/fng/`
   - Used for:
     - Fear & Greed metric card

All calls go through `CryptoService` and are never made directly from the browser.

---

## 3. Backend: Endpoints & Data Flow

### 3.1 Main Application

- `SpringBootInitialApplication`
  - Starts Spring Boot and embedded Tomcat on the configured port (default 8080)
  - Component‑scans `com.example` so that `CryptoController` and `CryptoService` are auto‑wired

### 3.2 Configuration

- `src/main/resources/application.properties` contains keys such as:
  - `coingecko.api.key=...` – API key for CoinGecko
  - (Optionally) `server.port=8080` or another port if you changed it

Spring injects `coingecko.api.key` into `CryptoService` with `@Value`.

### 3.3 REST Endpoints

1. **List crypto markets for tables and converter**

   - **Path**: `GET /api/crypto`
   - **Query param**:
     - `currency` (optional, default `usd`) – fiat currency code used as `vs_currency`
   - **Responsibility**:
     - Calls CoinGecko `coins/markets` with `vs_currency={currency}` and `order=market_cap_desc`
     - Builds a list of `CryptoCurrency` objects
   - **Response JSON (simplified)** – array of objects:
     ```json
     [
       {
         "name": "Bitcoin",
         "symbol": "BTC",
         "image": "https://.../btc.png",
         "current_price": 68000.12,
         "price_change_percentage_24h": 1.23,
         "market_cap_change_percentage_24h": 0.95
       },
       ...
     ]
     ```

2. **Global market metrics (Market Cap card)**

   - **Path**: `GET /api/market/global`
   - **Responsibility**:
     - Calls CoinGecko `/global`
     - Extracts:
       - `total_market_cap.usd`
       - `market_cap_change_percentage_24h_usd`
     - Wraps them into `GlobalMarketData`
   - **Response JSON (simplified)**:
     ```json
     {
       "total_market_cap_usd": 2500000000000.0,
       "market_cap_change_percentage_24h_usd": 0.89
     }
     ```

3. **Fear & Greed index (card)**

   - **Path**: `GET /api/market/fear-greed`
   - **Responsibility**:
     - Calls `https://api.alternative.me/fng/`
     - Takes the first entry in `data[]`
     - Extracts:
       - `value` (e.g. 52)
       - `value_classification` (e.g. `Neutral`)
       - `timestamp`
     - Wraps into `FearGreedData`
   - **Response JSON (simplified)**:
     ```json
     {
       "value": 52,
       "classification": "Neutral",
       "timestamp": 1711900800
     }
     ```

### 3.4 DTOs / Models

- `CryptoCurrency`
  - Fields (JSON names):
    - `name`
    - `symbol`
    - `image`
    - `current_price`
    - `price_change_percentage_24h`
    - `market_cap_change_percentage_24h`

- `GlobalMarketData`
  - `total_market_cap_usd`
  - `market_cap_change_percentage_24h_usd`

- `FearGreedData`
  - `value`
  - `classification`
  - `timestamp`

---

## 4. Frontend: Pages & Scripts

All frontend files live under `src/main/resources/static`.

### 4.1 Home Dashboard – `index.html`

**Main elements:**

1. **Top / Trending tabs & paginated table**
   - JS fetches data from:
     - `GET /api/crypto?currency={selectedFiat}`
   - After receiving the full list:
     - **Top tab**: uses the list as returned (descending by market cap)
     - **Trending tab**: sorts the list client‑side by `price_change_percentage_24h` to show biggest movers
   - **Pagination:**
     - Only 25 rows are shown per page
     - Uses in‑memory pagination (no extra backend calls)
     - Prev/Next buttons and a `Showing X–Y of Z` label under the table

2. **Metric cards (top row)**

   a. **Global Market Cap card**
   - Script in `global-metrics.js` calls `/api/market/global` periodically
   - Uses `total_market_cap_usd` and `market_cap_change_percentage_24h_usd` to render:
     - Current market cap (e.g. `$2.32T`)
     - 24h change %, colored green/red

   b. **Fear & Greed card**
   - `global-metrics.js` calls `/api/market/fear-greed`
   - Renders:
     - Index value (e.g. `52`)
     - Label (e.g. `Neutral`, `Greed`, `Extreme Fear`)
     - Color based on classification

   c. **Avg Market Cap Change card**
   - After each `/api/crypto` fetch, the page script:
     - Averages `market_cap_change_percentage_24h` across the current list (Top or Trending)
   - Updates:
     - Title value: e.g. `+0.89%`
     - Subtitle: short generic text (e.g. `Average • 24h cap`)

   d. **Avg Price Change card**
   - Uses the same `/api/crypto` response
   - Averages `price_change_percentage_24h` across the current list
   - Updates:
     - Title value: e.g. `+0.74%`
     - Subtitle: e.g. `Average • 24h price`

3. **Auto‑refresh behavior**
   - The dashboard periodically:
     - Re-fetches `/api/crypto` and recomputes:
       - Table rows
       - Avg Market Cap Change
       - Avg Price Change
     - Re-fetches `/api/market/global` and `/api/market/fear-greed` via `global-metrics.js`

### 4.2 Currency Converter – `currency.html`

**Purpose:**
- Let the user select a fiat currency and a coin, then see approximate conversions.

**Workflow:**
- On load / when fiat currency changes:
  - JS calls `GET /api/crypto?currency={selectedFiat}`
  - Populates a dropdown with coins from the response (`name`, `symbol`)
  - Uses `current_price` to calculate conversions between fiat and the selected coin
- The page also includes `global-metrics.js`, so it can optionally show the same Market Cap and Fear & Greed cards by including the same HTML ids.

### 4.3 News Page – `news.html`

**Purpose:**
- Display crypto‑related news articles (primarily static or client‑side logic).

**Backend usage:**
- Can reuse `global-metrics.js` and the metric card markup to display the same Market Cap and Fear & Greed cards, ensuring consistent values across pages.

---

## 5. Typical Request Flows

### 5.1 Top / Trending table row

1. Browser JS calls:
   - `GET /api/crypto?currency=usd`
2. Backend:
   - `CryptoController` forwards to `CryptoService.getTopCryptos("usd")`
   - `CryptoService` calls CoinGecko `coins/markets` with `vs_currency=usd` and `order=market_cap_desc`
   - Builds a list of `CryptoCurrency` DTOs
3. Frontend:
   - Receives JSON array
   - Stores full list in memory
   - Renders first 25 rows into the table
   - On Trending tab, sorts by `price_change_percentage_24h` before rendering

### 5.2 Market Cap card

1. Browser JS (`global-metrics.js`) calls:
   - `GET /api/market/global`
2. Backend:
   - `CryptoService.getGlobalMarketData()` calls CoinGecko `/global`
   - Extracts market cap + 24h change and returns `GlobalMarketData`
3. Frontend:
   - Receives JSON and formats the values into the Market Cap card

### 5.3 Fear & Greed card

1. Browser JS (`global-metrics.js`) calls:
   - `GET /api/market/fear-greed`
2. Backend:
   - `CryptoService.getFearGreedIndex()` calls Alternative.me `/fng/`
3. Frontend:
   - Updates the Fear & Greed card with the latest index and classification

### 5.4 Avg Market Cap / Price Change cards

1. When `/api/crypto` data is fetched (Top or Trending):
   - The page script computes:
     - Average of `market_cap_change_percentage_24h`
     - Average of `price_change_percentage_24h`
2. The two cards display these averages with short generic subtitles.

---

## 6. How to Run the App

From a PowerShell terminal on Windows:

1. Go to the `initial` module directory:
   ```powershell
   cd "C:\Users\Dell\Desktop\CryptoDash\initial"
   ```
2. Set `JAVA_HOME` for this terminal (adjust path if needed):
   ```powershell
   $env:JAVA_HOME = "C:\Program Files\Java\jdk-25"
   ```
3. Start Spring Boot with Maven Wrapper:
   ```powershell
   .\mvnw.cmd spring-boot:run
   ```
4. Open the app in a browser:
   - Dashboard: `http://localhost:8080/index.html`
   - Converter: `http://localhost:8080/currency.html`
   - News: `http://localhost:8080/news.html`

If the port is already in use, either stop the process on 8080 or set `server.port=8081` (or another port) in `application.properties` and use that port in the URLs above.

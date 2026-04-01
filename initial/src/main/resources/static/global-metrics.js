(function () {
  const REFRESH_MS = 30000;

  async function fetchGlobalMarketData() {
    const valueEl = document.getElementById("marketCapValue");
    const changeEl = document.getElementById("marketCapChange");

    if (!valueEl || !changeEl) return;

    try {
      const response = await fetch("/api/market/global");
      if (!response.ok) throw new Error("Global market API request failed");

      const data = await response.json();
      const totalCap = data.total_market_cap_usd;
      const change24h = data.market_cap_change_percentage_24h_usd;

      if (typeof totalCap === "number" && !isNaN(totalCap)) {
        valueEl.textContent = formatMarketCap(totalCap);
      }

      if (typeof change24h === "number" && !isNaN(change24h)) {
        const formattedChange = `${change24h >= 0 ? "+" : ""}${change24h.toFixed(2)}%`;
        changeEl.textContent = formattedChange;

        changeEl.classList.remove("text-success", "text-danger");
        changeEl.classList.add(change24h >= 0 ? "text-success" : "text-danger");
      }
    } catch (err) {
      console.error("Error fetching global market data:", err);
    }
  }

  async function fetchFearGreedData() {
    const valueEl = document.getElementById("fearGreedValue");
    const labelEl = document.getElementById("fearGreedLabel");

    if (!valueEl || !labelEl) return;

    try {
      const response = await fetch("/api/market/fear-greed");
      if (!response.ok) throw new Error("Fear & Greed API request failed");

      const data = await response.json();
      const indexValue = data.value;
      const classification = data.classification;

      if (typeof indexValue === "number" && !isNaN(indexValue)) {
        valueEl.textContent = indexValue;
      }

      if (classification) {
        labelEl.textContent = classification;

        const lower = classification.toLowerCase();
        labelEl.classList.remove("text-success", "text-danger", "text-warning");

        if (lower.includes("fear")) {
          labelEl.classList.add("text-danger");
        } else if (lower.includes("greed")) {
          labelEl.classList.add("text-success");
        } else {
          labelEl.classList.add("text-warning");
        }
      }
    } catch (err) {
      console.error("Error fetching fear & greed data:", err);
    }
  }

  function formatMarketCap(value) {
    if (value >= 1e12) {
      return `$${(value / 1e12).toFixed(2)}T`;
    }
    if (value >= 1e9) {
      return `$${(value / 1e9).toFixed(2)}B`;
    }
    if (value >= 1e6) {
      return `$${(value / 1e6).toFixed(2)}M`;
    }
    return new Intl.NumberFormat("en-US", {
      style: "currency",
      currency: "USD",
      maximumFractionDigits: 0,
    }).format(value);
  }

  function initGlobalMarketCap() {
    const hasMarketCap = document.getElementById("marketCapValue") && document.getElementById("marketCapChange");
    const hasFearGreed = document.getElementById("fearGreedValue") && document.getElementById("fearGreedLabel");

    if (!hasMarketCap && !hasFearGreed) return;

    if (hasMarketCap) {
      fetchGlobalMarketData();
      setInterval(fetchGlobalMarketData, REFRESH_MS);
    }

    if (hasFearGreed) {
      fetchFearGreedData();
      setInterval(fetchFearGreedData, REFRESH_MS);
    }
  }

  document.addEventListener("DOMContentLoaded", initGlobalMarketCap);
})();

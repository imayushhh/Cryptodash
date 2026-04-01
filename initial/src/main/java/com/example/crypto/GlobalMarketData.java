package com.example.crypto;

import com.fasterxml.jackson.annotation.JsonProperty;

public class GlobalMarketData {

    @JsonProperty("total_market_cap_usd")
    private final double totalMarketCapUsd;

    @JsonProperty("market_cap_change_percentage_24h_usd")
    private final double marketCapChangePercentage24hUsd;

    public GlobalMarketData(double totalMarketCapUsd, double marketCapChangePercentage24hUsd) {
        this.totalMarketCapUsd = totalMarketCapUsd;
        this.marketCapChangePercentage24hUsd = marketCapChangePercentage24hUsd;
    }

    public double getTotalMarketCapUsd() {
        return totalMarketCapUsd;
    }

    public double getMarketCapChangePercentage24hUsd() {
        return marketCapChangePercentage24hUsd;
    }
}

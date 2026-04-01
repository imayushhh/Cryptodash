package com.example.crypto;

import com.fasterxml.jackson.annotation.JsonProperty;

public class CryptoCurrency {

    private final String name;
    private final String symbol;
    private final String image;

    @JsonProperty("current_price")
    private final double currentPrice;

    @JsonProperty("price_change_percentage_24h")
    private final double priceChangePercentage24h;

    @JsonProperty("market_cap")
    private final double marketCap;

    @JsonProperty("market_cap_change_percentage_24h")
    private final double marketCapChangePercentage24h;

    @JsonProperty("ath")
    private final double allTimeHigh;

    @JsonProperty("circulating_supply")
    private final double circulatingSupply;

    @JsonProperty("total_supply")
    private final double totalSupply;

    public CryptoCurrency(String name,
                          String symbol,
                          String image,
                          double currentPrice,
                          double priceChangePercentage24h,
                          double marketCap,
                          double marketCapChangePercentage24h,
                          double allTimeHigh,
                          double circulatingSupply,
                          double totalSupply) {
        this.name = name;
        this.symbol = symbol;
        this.image = image;
        this.currentPrice = currentPrice;
        this.priceChangePercentage24h = priceChangePercentage24h;
        this.marketCap = marketCap;
        this.marketCapChangePercentage24h = marketCapChangePercentage24h;
        this.allTimeHigh = allTimeHigh;
        this.circulatingSupply = circulatingSupply;
        this.totalSupply = totalSupply;
    }

    public String getName() {
        return name;
    }

    public String getSymbol() {
        return symbol;
    }

    public String getImage() {
        return image;
    }

    public double getCurrentPrice() {
        return currentPrice;
    }

    public double getPriceChangePercentage24h() {
        return priceChangePercentage24h;
    }

    public double getMarketCap() {
        return marketCap;
    }

    public double getMarketCapChangePercentage24h() {
        return marketCapChangePercentage24h;
    }

    public double getAllTimeHigh() {
        return allTimeHigh;
    }

    public double getCirculatingSupply() {
        return circulatingSupply;
    }

    public double getTotalSupply() {
        return totalSupply;
    }
}

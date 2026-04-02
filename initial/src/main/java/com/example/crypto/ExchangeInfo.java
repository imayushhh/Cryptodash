package com.example.crypto;

import com.fasterxml.jackson.annotation.JsonProperty;

public class ExchangeInfo {

    private final String name;

    private final String image;

    private final String symbol;

    @JsonProperty("trust_score")
    private final Integer trustScore;

    @JsonProperty("trust_score_rank")
    private final Integer trustScoreRank;

    @JsonProperty("trade_volume_24h_btc")
    private final Double tradeVolume24hBtc;

    public ExchangeInfo(String name,
                        String image,
                        String symbol,
                        Integer trustScore,
                        Integer trustScoreRank,
                        Double tradeVolume24hBtc) {
        this.name = name;
        this.image = image;
        this.symbol = symbol;
        this.trustScore = trustScore;
        this.trustScoreRank = trustScoreRank;
        this.tradeVolume24hBtc = tradeVolume24hBtc;
    }

    public String getName() {
        return name;
    }

    public String getImage() {
        return image;
    }

    public String getSymbol() {
        return symbol;
    }

    public Integer getTrustScore() {
        return trustScore;
    }

    public Integer getTrustScoreRank() {
        return trustScoreRank;
    }

    public Double getTradeVolume24hBtc() {
        return tradeVolume24hBtc;
    }
}

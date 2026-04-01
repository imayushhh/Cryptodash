package com.example.crypto;

public class FearGreedData {

    private final int value;
    private final String classification;
    private final long timestamp;

    public FearGreedData(int value, String classification, long timestamp) {
        this.value = value;
        this.classification = classification;
        this.timestamp = timestamp;
    }

    public int getValue() {
        return value;
    }

    public String getClassification() {
        return classification;
    }

    public long getTimestamp() {
        return timestamp;
    }
}

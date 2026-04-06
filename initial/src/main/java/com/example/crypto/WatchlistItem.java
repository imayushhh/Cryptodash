package com.example.crypto;

public class WatchlistItem {

    private String symbol;
    private String name;
    private String image;

    public WatchlistItem() {
        // Default constructor for JSON deserialization.
    }

    public WatchlistItem(String symbol, String name, String image) {
        this.symbol = symbol;
        this.name = name;
        this.image = image;
    }

    public String getSymbol() {
        return symbol;
    }

    public void setSymbol(String symbol) {
        this.symbol = symbol;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getImage() {
        return image;
    }

    public void setImage(String image) {
        this.image = image;
    }
}

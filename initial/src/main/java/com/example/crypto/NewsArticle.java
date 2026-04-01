package com.example.crypto;

public class NewsArticle {
    private final String title;
    private final String description;
    private final String url;
    private final String imageUrl;
    private final String sourceName;
    private final String author;
    private final String publishedAt;

    public NewsArticle(String title,
                       String description,
                       String url,
                       String imageUrl,
                       String sourceName,
                       String author,
                       String publishedAt) {
        this.title = title;
        this.description = description;
        this.url = url;
        this.imageUrl = imageUrl;
        this.sourceName = sourceName;
        this.author = author;
        this.publishedAt = publishedAt;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public String getUrl() {
        return url;
    }

    public String getImageUrl() {
        return imageUrl;
    }

    public String getSourceName() {
        return sourceName;
    }

    public String getAuthor() {
        return author;
    }

    public String getPublishedAt() {
        return publishedAt;
    }
}

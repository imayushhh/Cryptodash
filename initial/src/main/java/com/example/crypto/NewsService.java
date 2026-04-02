package com.example.crypto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

@Service
public class NewsService {

    private static final String BASE_URL = "https://newsapi.org/v2";

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final String apiKey;

    public NewsService(WebClient.Builder webClientBuilder,
                       ObjectMapper objectMapper,
                       @Value("${newsapi.api.key:}") String apiKey) {
        this.webClient = webClientBuilder
                .baseUrl(BASE_URL)
            .defaultHeader("Accept", MediaType.APPLICATION_JSON_VALUE)
                .build();
        this.objectMapper = objectMapper;
        this.apiKey = apiKey;
    }

    public List<NewsArticle> getLatestCryptoNews() {
        if (apiKey == null || apiKey.isBlank()) {
            return getDemoNews();
        }

        try {
            Instant now = Instant.now();
            Instant fromInstant = now.minus(Duration.ofHours(48));
            String fromDate = LocalDate.now(ZoneOffset.UTC).minusDays(2).toString();

            String responseBody = webClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/everything")
                            .queryParam("q", "cryptocurrency")
                    .queryParam("from", fromDate)
                            .queryParam("sortBy", "publishedAt")
                    .queryParam("apiKey", apiKey)
                            .queryParam("pageSize", 50)
                            .build())
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(8))
                    .onErrorResume(ex -> Mono.empty())
                    .block();

            if (responseBody == null || responseBody.isBlank()) {
                return getDemoNews();
            }

            JsonNode root = objectMapper.readTree(responseBody);
            String status = root.path("status").asText("");
            if (!"ok".equalsIgnoreCase(status)) {
                return getDemoNews();
            }

            JsonNode articlesNode = root.path("articles");
            if (!articlesNode.isArray() || articlesNode.isEmpty()) {
                return getDemoNews();
            }

            List<NewsArticle> articles = new ArrayList<>();
            for (JsonNode articleNode : articlesNode) {
                String title = articleNode.path("title").asText("");
                String description = articleNode.path("description").asText("");
                String url = articleNode.path("url").asText("");
                String imageUrl = articleNode.path("urlToImage").asText("");
                String sourceName = articleNode.path("source").path("name").asText("");
                String author = articleNode.path("author").asText("");
                String publishedAt = articleNode.path("publishedAt").asText("");

                if (title == null || title.isBlank() || url == null || url.isBlank()) {
                    continue;
                }

                Instant publishedInstant = parsePublishedAt(publishedAt);
                if (publishedInstant == null || publishedInstant.isBefore(fromInstant) || publishedInstant.isAfter(now)) {
                    continue;
                }

                articles.add(new NewsArticle(
                        title,
                        description,
                        url,
                        imageUrl,
                        sourceName,
                        author,
                        publishedAt
                ));
            }

            return articles.isEmpty() ? getDemoNews() : articles;
        } catch (Exception ex) {
            return getDemoNews();
        }
    }

    private Instant parsePublishedAt(String publishedAt) {
        if (publishedAt == null || publishedAt.isBlank()) {
            return null;
        }

        try {
            return Instant.parse(publishedAt);
        } catch (DateTimeParseException ignored) {
            // Fall through to alternate parser.
        }

        try {
            LocalDateTime dt = LocalDateTime.parse(publishedAt, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            return dt.toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private List<NewsArticle> getDemoNews() {
        List<NewsArticle> demo = new ArrayList<>();
        demo.add(new NewsArticle(
                "Bitcoin hits new milestone as institutions accumulate",
                "Institutional investors continue to accumulate BTC, pushing on-chain metrics to fresh highs.",
                "https://example.com/bitcoin-institutions",
                "https://via.placeholder.com/300x180?text=BTC",
                "CryptoDash Demo",
                "CryptoDash Research",
                Instant.now().toString()
        ));
        demo.add(new NewsArticle(
                "Ethereum scaling upgrade drives DeFi activity surge",
                "Layer-2 rollups and lower gas fees are reigniting DeFi usage across major protocols.",
                "https://example.com/ethereum-scaling",
                "https://via.placeholder.com/300x180?text=ETH",
                "CryptoDash Demo",
                "CryptoDash Research",
                Instant.now().minus(Duration.ofHours(2)).toString()
        ));
        demo.add(new NewsArticle(
                "Solana ecosystem attracts new memecoins and NFT projects",
                "High throughput and low fees continue to draw new retail interest to Solana.",
                "https://example.com/solana-ecosystem",
                "https://via.placeholder.com/300x180?text=SOL",
                "CryptoDash Demo",
                "CryptoDash Research",
                Instant.now().minus(Duration.ofHours(4)).toString()
        ));
        return demo;
    }
}

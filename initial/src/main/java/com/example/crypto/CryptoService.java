package com.example.crypto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

@Service
public class CryptoService {

    private static final String BASE_URL = "https://api.coingecko.com/api/v3";

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final String apiKey;

    public CryptoService(WebClient.Builder webClientBuilder,
                         ObjectMapper objectMapper,
                         @Value("${coingecko.api.key:}") String apiKey) {
        this.webClient = webClientBuilder
                .baseUrl(BASE_URL)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .build();
        this.objectMapper = objectMapper;
        this.apiKey = apiKey;
    }

    public List<CryptoCurrency> getTopCryptos(String currency) {
        String vsCurrency = (currency == null || currency.isBlank())
                ? "usd"
                : currency.toLowerCase(Locale.ROOT);

        String coinGeckoKey = resolveCoinGeckoKey();

        try {
            String responseBody = webClient.get()
                        .uri(uriBuilder -> uriBuilder
                            .path("/coins/markets")
                            .queryParam("vs_currency", vsCurrency)
                            .queryParam("order", "market_cap_desc")
                            .queryParam("price_change_percentage", "24h")
                            .build())
                        .header("x-cg-demo-api-key", coinGeckoKey)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(8))
                    .onErrorResume(ex -> Mono.empty())
                    .block();

            if (responseBody == null || responseBody.isBlank()) {
                return getDemoData(vsCurrency.toUpperCase(Locale.ROOT));
            }

            JsonNode root = objectMapper.readTree(responseBody);
            if (!root.isArray()) {
                return Collections.emptyList();
            }

            List<CryptoCurrency> result = new ArrayList<>();
            for (JsonNode coinNode : root) {
                String name = coinNode.path("name").asText();
                String symbol = coinNode.path("symbol").asText().toUpperCase(Locale.ROOT);

                double price = coinNode.path("current_price").asDouble();
                double change24h = coinNode.path("price_change_percentage_24h").asDouble();
                double marketCap = coinNode.path("market_cap").asDouble();
                double marketCapChange24h = coinNode.path("market_cap_change_percentage_24h").asDouble();
                double ath = coinNode.path("ath").asDouble();

                double circulatingSupply = coinNode.path("circulating_supply").asDouble();
                double totalSupply = coinNode.path("total_supply").asDouble();

                String imageUrl = coinNode.path("image").asText();

                result.add(new CryptoCurrency(name,
                        symbol,
                        imageUrl,
                        price,
                        change24h,
                        marketCap,
                        marketCapChange24h,
                        ath,
                        circulatingSupply,
                        totalSupply));
            }

            return result;
        } catch (Exception ex) {
            // On any parsing or network issue, fall back to demo data
            return getDemoData(vsCurrency.toUpperCase(Locale.ROOT));
        }
    }

    public GlobalMarketData getGlobalMarketData() {
        String coinGeckoKey = resolveCoinGeckoKey();

        try {
            String responseBody = webClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/global")
                            .build())
                    .header("x-cg-demo-api-key", coinGeckoKey)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(8))
                    .onErrorResume(ex -> Mono.empty())
                    .block();

            if (responseBody == null || responseBody.isBlank()) {
                return getDemoGlobalData();
            }

            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode dataNode = root.path("data");

            double totalMarketCapUsd = dataNode.path("total_market_cap").path("usd").asDouble();
            double marketCapChange24hUsd = dataNode.path("market_cap_change_percentage_24h_usd").asDouble();

            return new GlobalMarketData(totalMarketCapUsd, marketCapChange24hUsd);
        } catch (Exception ex) {
            return getDemoGlobalData();
        }
    }

    public List<ExchangeInfo> getExchanges() {
        String coinGeckoKey = resolveCoinGeckoKey();

        try {
            String responseBody = webClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/exchanges")
                            .queryParam("per_page", "250")
                            .build())
                    .header("x-cg-demo-api-key", coinGeckoKey)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(10))
                    .onErrorResume(ex -> Mono.empty())
                    .block();

            if (responseBody == null || responseBody.isBlank()) {
                return getDemoExchanges();
            }

            JsonNode root = objectMapper.readTree(responseBody);
            if (!root.isArray()) {
                return getDemoExchanges();
            }

            List<ExchangeInfo> exchanges = new ArrayList<>();
            for (JsonNode exchangeNode : root) {
                String name = exchangeNode.path("name").asText();
                String image = exchangeNode.path("image").asText();
                String symbol = exchangeNode.path("id").asText();

                Integer trustScore = exchangeNode.path("trust_score").isNumber()
                        ? exchangeNode.path("trust_score").asInt()
                        : null;
                Integer trustScoreRank = exchangeNode.path("trust_score_rank").isNumber()
                        ? exchangeNode.path("trust_score_rank").asInt()
                        : null;
                Double tradeVolume24hBtc = exchangeNode.path("trade_volume_24h_btc").isNumber()
                        ? exchangeNode.path("trade_volume_24h_btc").asDouble()
                        : null;

                exchanges.add(new ExchangeInfo(
                        name,
                        image,
                        symbol,
                        trustScore,
                        trustScoreRank,
                        tradeVolume24hBtc
                ));
            }

            return exchanges;
        } catch (Exception ex) {
            return getDemoExchanges();
        }
    }

    public FearGreedData getFearGreedIndex() {
        try {
            WebClient fearGreedClient = WebClient.builder()
                    .baseUrl("https://api.alternative.me")
                    .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                    .build();

            String responseBody = fearGreedClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/fng/")
                            .build())
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(8))
                    .onErrorResume(ex -> Mono.empty())
                    .block();

            if (responseBody == null || responseBody.isBlank()) {
                return getDemoFearGreedData();
            }

            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode dataArray = root.path("data");
            if (!dataArray.isArray() || dataArray.isEmpty()) {
                return getDemoFearGreedData();
            }

            JsonNode first = dataArray.get(0);
            int value;
            try {
                value = Integer.parseInt(first.path("value").asText("50"));
            } catch (NumberFormatException ex) {
                value = 50;
            }

            String classification = first.path("value_classification").asText("Neutral");
            long timestamp = first.path("timestamp").asLong(0L);

            return new FearGreedData(value, classification, timestamp);
        } catch (Exception ex) {
            return getDemoFearGreedData();
        }
    }

    private List<CryptoCurrency> getDemoData(String convert) {
        // Simple static demo data so the UI works before wiring the real key
        List<CryptoCurrency> demo = new ArrayList<>();
        demo.add(new CryptoCurrency("Bitcoin", "BTC", "https://via.placeholder.com/28?text=BTC", 68000.00, 1.25, 1_340_000_000_000.00, 1.10, 69000.00, 19_700_000.0, 21_000_000.0));
        demo.add(new CryptoCurrency("Ethereum", "ETH", "https://via.placeholder.com/28?text=ETH", 3400.50, -0.85, 408_000_000_000.00, -0.40, 4800.00, 120_000_000.0, 120_000_000.0));
        demo.add(new CryptoCurrency("Tether", "USDT", "https://via.placeholder.com/28?text=USDT", 1.00, 0.02, 90_000_000_000.00, 0.01, 1.05, 90_000_000_000.0, 90_000_000_000.0));
        demo.add(new CryptoCurrency("Solana", "SOL", "https://via.placeholder.com/28?text=SOL", 150.75, 3.10, 69_000_000_000.00, 2.50, 260.00, 450_000_000.0, 570_000_000.0));
        demo.add(new CryptoCurrency("BNB", "BNB", "https://via.placeholder.com/28?text=BNB", 420.10, 0.45, 62_000_000_000.00, 0.30, 690.00, 155_000_000.0, 200_000_000.0));
        return demo;
    }

    private GlobalMarketData getDemoGlobalData() {
        double demoTotalMarketCapUsd = 2_320_000_000_000.0;
        double demoMarketCapChange24hUsd = 1.19;
        return new GlobalMarketData(demoTotalMarketCapUsd, demoMarketCapChange24hUsd);
    }

    private FearGreedData getDemoFearGreedData() {
        int demoValue = 50;
        String demoClassification = "Neutral";
        long demoTimestamp = 0L;
        return new FearGreedData(demoValue, demoClassification, demoTimestamp);
    }

    private List<ExchangeInfo> getDemoExchanges() {
        List<ExchangeInfo> demo = new ArrayList<>();
        demo.add(new ExchangeInfo("Binance", "https://assets.coingecko.com/markets/images/52/small/binance.jpg", "binance", 10, 1, 1800000.0));
        demo.add(new ExchangeInfo("Coinbase Exchange", "https://assets.coingecko.com/markets/images/23/small/Coinbase_Coin_Primary.png", "gdax", 10, 2, 780000.0));
        demo.add(new ExchangeInfo("Kraken", "https://assets.coingecko.com/markets/images/29/small/kraken.jpg", "kraken", 10, 3, 620000.0));
        demo.add(new ExchangeInfo("Bybit", "https://assets.coingecko.com/markets/images/698/small/bybit_spot.png", "bybit_spot", 10, 4, 540000.0));
        return demo;
    }

    private String resolveCoinGeckoKey() {
        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("WARNING: COINGECKO_API_KEY environment variable is not set. API calls will fail and demo data will be used.");
            return "";
        }
        return apiKey;
    }
}

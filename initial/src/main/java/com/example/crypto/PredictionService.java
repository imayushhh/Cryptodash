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
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class PredictionService {

    private static final String DEFAULT_AI_SERVICE_URL = "https://cryptodash-ai.onrender.com";

    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    public PredictionService(WebClient.Builder webClientBuilder,
                             ObjectMapper objectMapper,
                             @Value("${ai.service.url:https://cryptodash-ai.onrender.com}") String aiServiceUrl) {
        String baseUrl = (aiServiceUrl == null || aiServiceUrl.isBlank())
                ? DEFAULT_AI_SERVICE_URL
                : aiServiceUrl.trim();

        this.webClient = webClientBuilder
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .build();
        this.objectMapper = objectMapper;
    }

    public JsonNode getPrediction(String coinId) {
        try {
            String responseBody = webClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/predict/{coinId}")
                            .build(coinId))
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(60))
                    .onErrorResume(ex -> Mono.empty())
                    .block();

            if (responseBody == null || responseBody.isBlank()) {
                return aiServiceUnavailable();
            }

            return objectMapper.readTree(responseBody);
        } catch (Exception ex) {
            return aiServiceUnavailable();
        }
    }

    public JsonNode getAllPredictions() {
        try {
            String responseBody = webClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/predict/all")
                            .build())
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(60))
                    .onErrorResume(ex -> Mono.empty())
                    .block();

            if (responseBody == null || responseBody.isBlank()) {
                return aiServiceUnavailable();
            }

            return objectMapper.readTree(responseBody);
        } catch (Exception ex) {
            return aiServiceUnavailable();
        }
    }

    public JsonNode getAdvisorRecommendation(double budget, String risk, int numCoins) {
        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("budget", budget);
        requestBody.put("risk", risk == null ? "" : risk);
        requestBody.put("num_coins", numCoins);

        try {
            String responseBody = webClient.post()
                    .uri(uriBuilder -> uriBuilder
                            .path("/advisor")
                            .build())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(60))
                    .onErrorResume(ex -> Mono.empty())
                    .block();

            if (responseBody == null || responseBody.isBlank()) {
                return aiServiceUnavailable();
            }

            return objectMapper.readTree(responseBody);
        } catch (Exception ex) {
            return aiServiceUnavailable();
        }
    }

    private JsonNode aiServiceUnavailable() {
        return objectMapper.createObjectNode().put("error", "AI service unavailable");
    }
}
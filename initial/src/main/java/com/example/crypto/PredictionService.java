package com.example.crypto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.concurrent.TimeoutException;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class PredictionService {

    private static final String DEFAULT_AI_SERVICE_URL = "https://cryptodash-ai.onrender.com";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration RETRY_BACKOFF = Duration.ofSeconds(2);
    private static final int RETRY_ATTEMPTS = 1;

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
            String responseBody = fetchResponseBody(webClient.get()
                .uri(uriBuilder -> uriBuilder
                    .path("/predict/{coinId}")
                    .build(coinId))
                .retrieve()
                .bodyToMono(String.class));

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
            String responseBody = fetchResponseBody(webClient.get()
                .uri(uriBuilder -> uriBuilder
                    .path("/predict/all")
                    .build())
                .retrieve()
                .bodyToMono(String.class));

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
            String responseBody = fetchResponseBody(webClient.post()
                    .uri(uriBuilder -> uriBuilder
                            .path("/advisor")
                            .build())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(requestBody)
                    .retrieve()
                .bodyToMono(String.class));

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

    private String fetchResponseBody(Mono<String> responseMono) {
        try {
            return responseMono
                    .timeout(REQUEST_TIMEOUT)
                    .retryWhen(Retry.backoff(RETRY_ATTEMPTS, RETRY_BACKOFF)
                            .filter(this::isRetryableAiServiceFailure))
                    .block();
        } catch (Exception ex) {
            return null;
        }
    }

    private boolean isRetryableAiServiceFailure(Throwable throwable) {
        if (throwable instanceof WebClientResponseException responseException) {
            return responseException.getStatusCode().is5xxServerError();
        }

        return throwable instanceof WebClientRequestException
                || throwable instanceof TimeoutException;
    }
}
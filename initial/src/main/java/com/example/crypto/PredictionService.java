package com.example.crypto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.ChannelOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.concurrent.TimeoutException;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class PredictionService {

    private static final Logger log = LoggerFactory.getLogger(PredictionService.class);
    private static final String DEFAULT_AI_SERVICE_URL = "https://cryptodash-ai.onrender.com";
    // Render free tier cold starts can take up to 120s — keep the connection alive for 150s
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(150);
    private static final Duration RETRY_BACKOFF = Duration.ofSeconds(5);
    private static final int RETRY_ATTEMPTS = 3;

    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    public PredictionService(WebClient.Builder webClientBuilder,
                             ObjectMapper objectMapper,
                             @Value("${ai.service.url:https://cryptodash-ai.onrender.com}") String aiServiceUrl) {
        String baseUrl = normalizeBaseUrl(aiServiceUrl);

        // Configure Netty HttpClient with an explicit responseTimeout so the underlying
        // TCP connection stays open long enough for Render's cold start to complete.
        // Without this, the default connector may close the connection before the
        // reactive .timeout() fires, causing Render to abort its boot.
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10_000)
                .responseTimeout(REQUEST_TIMEOUT);

        this.webClient = webClientBuilder
                .baseUrl(baseUrl)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .build();
        this.objectMapper = objectMapper;

        log.info("PredictionService forwarding AI requests to {}", baseUrl);
    }

    public JsonNode getPrediction(String coinId) {
        String safeCoinId = coinId == null ? "" : coinId.trim();
        if (safeCoinId.isBlank()) {
            return aiServiceUnavailable();
        }

        return proxyAiJsonResponse(
                "prediction/" + safeCoinId,
                webClient.get()
                        .uri(uriBuilder -> uriBuilder
                                .path("/predict/{coinId}")
                                .build(safeCoinId))
                        .retrieve()
                        .bodyToMono(String.class)
        );
    }

    public JsonNode getAllPredictions() {
        return proxyAiJsonResponse(
                "prediction/all",
                webClient.get()
                        .uri(uriBuilder -> uriBuilder
                                .path("/predict/all")
                                .build())
                        .retrieve()
                        .bodyToMono(String.class)
        );
    }

    public JsonNode getAdvisorRecommendation(double budget, String risk, int numCoins) {
        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("budget", budget);
        requestBody.put("risk", risk == null ? "" : risk.trim());
        requestBody.put("num_coins", numCoins);

        return proxyAiJsonResponse(
                "advisor",
                webClient.post()
                        .uri(uriBuilder -> uriBuilder
                                .path("/advisor")
                                .build())
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(requestBody)
                        .retrieve()
                        .bodyToMono(String.class)
        );
    }

    private JsonNode proxyAiJsonResponse(String operation, Mono<String> responseMono) {
        try {
            String responseBody = fetchResponseBody(operation, responseMono);

            if (responseBody == null || responseBody.isBlank()) {
                log.warn("Render AI response was empty for {}", operation);
                return aiServiceUnavailable();
            }

            return objectMapper.readTree(responseBody);
        } catch (Exception ex) {
            log.warn("Render AI request failed for {}: {}", operation, ex.getMessage());
            return aiServiceUnavailable();
        }
    }

    private JsonNode aiServiceUnavailable() {
        return objectMapper.createObjectNode().put("error", "AI service unavailable");
    }

    private String fetchResponseBody(String operation, Mono<String> responseMono) {
        try {
            log.info("Forwarding Ask AI request: {}", operation);
            return responseMono
                    .timeout(REQUEST_TIMEOUT)
                    .retryWhen(Retry.backoff(RETRY_ATTEMPTS, RETRY_BACKOFF)
                            .filter(this::isRetryableAiServiceFailure))
                    .block();
        } catch (Exception ex) {
            log.warn("Render AI request exhausted for {}: {}", operation, ex.getMessage());
            return null;
        }
    }

    private String normalizeBaseUrl(String aiServiceUrl) {
        String baseUrl = (aiServiceUrl == null || aiServiceUrl.isBlank())
                ? DEFAULT_AI_SERVICE_URL
                : aiServiceUrl.trim();

        return baseUrl.replaceAll("/+$", "");
    }

    private boolean isRetryableAiServiceFailure(Throwable throwable) {
        if (throwable instanceof WebClientResponseException responseException) {
            return responseException.getStatusCode().is5xxServerError();
        }

        return throwable instanceof WebClientRequestException
                || throwable instanceof TimeoutException;
    }
}
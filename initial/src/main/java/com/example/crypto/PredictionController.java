package com.example.crypto;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class PredictionController {

    private final PredictionService predictionService;

    public PredictionController(PredictionService predictionService) {
        this.predictionService = predictionService;
    }

    @GetMapping("/prediction/{coinId}")
    public ResponseEntity<JsonNode> getPrediction(@PathVariable String coinId) {
        JsonNode response = predictionService.getPrediction(coinId);
        return toResponseEntity(response);
    }

    @GetMapping("/prediction/all")
    public ResponseEntity<JsonNode> getAllPredictions() {
        JsonNode response = predictionService.getAllPredictions();
        return toResponseEntity(response);
    }

    @PostMapping("/advisor")
    public ResponseEntity<JsonNode> getAdvisorRecommendation(@RequestBody AdvisorRequest request) {
        JsonNode response = predictionService.getAdvisorRecommendation(
                request.getBudget(),
                request.getRisk(),
                request.getNumCoins()
        );
        return toResponseEntity(response);
    }

    private ResponseEntity<JsonNode> toResponseEntity(JsonNode response) {
        if (response != null && response.has("error")) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(response);
        }

        return ResponseEntity.ok(response);
    }

    public static class AdvisorRequest {
        private double budget;
        private String risk;
        private int numCoins;

        public double getBudget() {
            return budget;
        }

        public void setBudget(double budget) {
            this.budget = budget;
        }

        public String getRisk() {
            return risk;
        }

        public void setRisk(String risk) {
            this.risk = risk;
        }

        public int getNumCoins() {
            return numCoins;
        }

        public void setNumCoins(int numCoins) {
            this.numCoins = numCoins;
        }
    }
}
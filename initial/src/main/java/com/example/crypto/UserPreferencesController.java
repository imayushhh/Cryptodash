package com.example.crypto;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/user")
public class UserPreferencesController {

    private final UserProfileService userProfileService;

    public UserPreferencesController(UserProfileService userProfileService) {
        this.userProfileService = userProfileService;
    }

    @GetMapping("/watchlist")
    public ResponseEntity<?> getWatchlist(@RequestHeader(name = "Authorization", required = false) String authorization) {
        String idToken = extractBearerToken(authorization);
        if (idToken.isBlank()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(error("Missing or invalid Authorization header. Use Bearer <Firebase ID token>."));
        }

        try {
            List<WatchlistItem> watchlist = userProfileService.getWatchlist(idToken);
            return ResponseEntity.ok(watchlist);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(error(ex.getMessage()));
        } catch (IllegalStateException ex) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(error(ex.getMessage()));
        }
    }

    @PutMapping("/watchlist")
    public ResponseEntity<?> saveWatchlist(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestBody(required = false) WatchlistUpdateRequest request
    ) {
        String idToken = extractBearerToken(authorization);
        if (idToken.isBlank()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(error("Missing or invalid Authorization header. Use Bearer <Firebase ID token>."));
        }

        List<WatchlistItem> incoming = request == null ? Collections.emptyList() : request.getItems();

        try {
            List<WatchlistItem> saved = userProfileService.saveWatchlist(idToken, incoming);
            return ResponseEntity.ok(saved);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(error(ex.getMessage()));
        } catch (IllegalStateException ex) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(error(ex.getMessage()));
        }
    }

    private String extractBearerToken(String authorization) {
        if (authorization == null || authorization.isBlank()) {
            return "";
        }

        if (!authorization.startsWith("Bearer ")) {
            return "";
        }

        return authorization.substring(7).trim();
    }

    private Map<String, Object> error(String message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("error", message == null ? "Unknown error" : message);
        return payload;
    }

    public static class WatchlistUpdateRequest {
        private List<WatchlistItem> items;

        public List<WatchlistItem> getItems() {
            return items;
        }

        public void setItems(List<WatchlistItem> items) {
            this.items = items;
        }
    }
}

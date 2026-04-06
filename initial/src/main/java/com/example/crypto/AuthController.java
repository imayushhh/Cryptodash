package com.example.crypto;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserProfileService userProfileService;
    private final String webApiKey;
    private final String webAuthDomain;
    private final String webProjectId;
    private final String webAppId;

    public AuthController(UserProfileService userProfileService,
                          @Value("${firebase.web.api-key:}") String webApiKey,
                          @Value("${firebase.web.auth-domain:}") String webAuthDomain,
                          @Value("${firebase.web.project-id:}") String webProjectId,
                          @Value("${firebase.web.app-id:}") String webAppId) {
        this.userProfileService = userProfileService;
        this.webApiKey = webApiKey;
        this.webAuthDomain = webAuthDomain;
        this.webProjectId = webProjectId;
        this.webAppId = webAppId;
    }

    @GetMapping("/web-config")
    public Map<String, String> getWebConfig() {
        Map<String, String> config = new LinkedHashMap<>();
        config.put("apiKey", safe(webApiKey));
        config.put("authDomain", safe(webAuthDomain));
        config.put("projectId", safe(webProjectId));
        config.put("appId", safe(webAppId));
        return config;
    }

    @PostMapping("/sync-profile")
    public ResponseEntity<Map<String, Object>> syncProfile(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestBody(required = false) ProfileSyncRequest request
    ) {
        String idToken = extractBearerToken(authorization);
        if (idToken.isBlank()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(error("Missing or invalid Authorization header. Use Bearer <Firebase ID token>."));
        }

        String displayName = request == null ? "" : safe(request.getDisplayName());
        String phone = request == null ? "" : safe(request.getPhone());

        try {
            Map<String, Object> profile = userProfileService.syncProfile(idToken, displayName, phone);
            return ResponseEntity.ok(profile);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(error(ex.getMessage()));
        } catch (IllegalStateException ex) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(error(ex.getMessage()));
        }
    }

    @PostMapping("/delete-account")
    public ResponseEntity<Map<String, Object>> deleteAccount(
            @RequestHeader(name = "Authorization", required = false) String authorization
    ) {
        String idToken = extractBearerToken(authorization);
        if (idToken.isBlank()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(error("Missing or invalid Authorization header. Use Bearer <Firebase ID token>."));
        }

        try {
            userProfileService.deleteAccount(idToken);
            return ResponseEntity.ok(success("Account deleted."));
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

    private Map<String, Object> success(String message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("message", message == null ? "Success" : message);
        return payload;
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    public static class ProfileSyncRequest {
        private String displayName;
        private String phone;

        public String getDisplayName() {
            return displayName;
        }

        public void setDisplayName(String displayName) {
            this.displayName = displayName;
        }

        public String getPhone() {
            return phone;
        }

        public void setPhone(String phone) {
            this.phone = phone;
        }
    }
}

package com.example.crypto;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.FieldValue;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.SetOptions;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.FirebaseToken;
import com.google.firebase.cloud.FirestoreClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Service
public class UserProfileService {

    private static final Logger logger = LoggerFactory.getLogger(UserProfileService.class);

    private final String credentialsPath;
    private final String projectId;
    private final String usersCollection;
    private final String databaseId;

    private volatile Firestore firestore;
    private volatile FirebaseAuth firebaseAuth;
    private volatile boolean initializationAttempted;

    public UserProfileService(@Value("${firebase.credentials.path:}") String credentialsPath,
                              @Value("${firebase.project-id:}") String projectId,
                              @Value("${firebase.firestore.users-collection:crypto_users}") String usersCollection,
                              @Value("${firebase.firestore.database-id:(default)}") String databaseId) {
        this.credentialsPath = credentialsPath;
        this.projectId = projectId;
        this.usersCollection = usersCollection;
        this.databaseId = databaseId;
    }

    public Map<String, Object> syncProfile(String idToken, String requestedDisplayName, String requestedPhone) {
        Firestore db = requireFirestore();

        try {
            FirebaseToken decoded = verifyToken(idToken);
            String uid = decoded.getUid();
            String email = firstNonBlank(decoded.getEmail(), "");
            String displayName = firstNonBlank(requestedDisplayName, decoded.getName(), defaultDisplayName(uid, email));
            String tokenPhone = readClaimAsText(decoded, "phone_number");
            String phone = firstNonBlank(requestedPhone, tokenPhone, "");
            String provider = resolveProvider(decoded);

            DocumentReference userDocRef = db.collection(usersCollection).document(uid);
            DocumentSnapshot existing = userDocRef.get().get(10, TimeUnit.SECONDS);

            Map<String, Object> updates = new LinkedHashMap<>();
            updates.put("uid", uid);
            updates.put("email", email);
            updates.put("displayName", displayName);
            updates.put("phone", phone);
            updates.put("provider", provider);
            updates.put("updatedAt", FieldValue.serverTimestamp());
            updates.put("lastLoginAt", FieldValue.serverTimestamp());

            if (!existing.exists()) {
                updates.put("createdAt", FieldValue.serverTimestamp());
            }

            userDocRef.set(updates, SetOptions.merge()).get(10, TimeUnit.SECONDS);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("uid", uid);
            response.put("email", email);
            response.put("displayName", displayName);
            response.put("phone", phone);
            response.put("provider", provider);
            response.put("created", !existing.exists());
            return response;
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to write user profile to Firestore.", ex);
        }
    }

    public List<WatchlistItem> getWatchlist(String idToken) {
        Firestore db = requireFirestore();

        try {
            FirebaseToken decoded = verifyToken(idToken);
            String uid = decoded.getUid();

            DocumentSnapshot userDoc = db.collection(usersCollection)
                    .document(uid)
                    .get()
                    .get(10, TimeUnit.SECONDS);

            return readWatchlist(userDoc);
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to read watchlist from Firestore.", ex);
        }
    }

    public List<WatchlistItem> saveWatchlist(String idToken, List<WatchlistItem> requestedItems) {
        Firestore db = requireFirestore();

        try {
            FirebaseToken decoded = verifyToken(idToken);
            String uid = decoded.getUid();
            String email = firstNonBlank(decoded.getEmail(), "");
            String displayName = firstNonBlank(decoded.getName(), defaultDisplayName(uid, email));

            List<WatchlistItem> normalized = normalizeWatchlist(requestedItems);

            DocumentReference userDocRef = db.collection(usersCollection).document(uid);
            DocumentSnapshot existing = userDocRef.get().get(10, TimeUnit.SECONDS);

            Map<String, Object> updates = new LinkedHashMap<>();
            updates.put("uid", uid);
            updates.put("email", email);
            updates.put("displayName", displayName);
            updates.put("watchlist", watchlistToFirestore(normalized));
            updates.put("watchlistUpdatedAt", FieldValue.serverTimestamp());
            updates.put("updatedAt", FieldValue.serverTimestamp());

            if (!existing.exists()) {
                updates.put("createdAt", FieldValue.serverTimestamp());
            }

            userDocRef.set(updates, SetOptions.merge()).get(10, TimeUnit.SECONDS);
            return normalized;
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to save watchlist to Firestore.", ex);
        }
    }

    public void deleteAccount(String idToken) {
        Firestore db = requireFirestore();
        FirebaseAuth auth = requireFirebaseAuth();

        try {
            FirebaseToken decoded = verifyToken(idToken);
            String uid = decoded.getUid();

            db.collection(usersCollection)
                    .document(uid)
                    .delete()
                    .get(10, TimeUnit.SECONDS);

            auth.deleteUser(uid);
            logger.info("Deleted Firebase account and Firestore profile for uid {}", uid);
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to delete Firebase account.", ex);
        }
    }

    private Firestore getFirestore() {
        ensureInitialized();
        return firestore;
    }

    private FirebaseAuth getFirebaseAuth() {
        ensureInitialized();
        return firebaseAuth;
    }

    private Firestore requireFirestore() {
        Firestore db = getFirestore();
        if (db == null) {
            throw new IllegalStateException("Firebase is not initialized. Check service account path and Firebase configuration.");
        }
        return db;
    }

    private FirebaseAuth requireFirebaseAuth() {
        FirebaseAuth auth = getFirebaseAuth();
        if (auth == null) {
            throw new IllegalStateException("Firebase Authentication is not initialized.");
        }
        return auth;
    }

    private FirebaseToken verifyToken(String idToken) {
        FirebaseAuth auth = getFirebaseAuth();
        if (auth == null) {
            throw new IllegalStateException("Firebase Authentication is not initialized.");
        }

        try {
            return auth.verifyIdToken(idToken);
        } catch (FirebaseAuthException ex) {
            throw new IllegalArgumentException("Invalid Firebase ID token.", ex);
        }
    }

    private void ensureInitialized() {
        if (firestore != null && firebaseAuth != null) {
            return;
        }

        if (initializationAttempted) {
            return;
        }

        synchronized (this) {
            if ((firestore != null && firebaseAuth != null) || initializationAttempted) {
                return;
            }

            initializationAttempted = true;

            try {
                FirebaseApp app;
                List<FirebaseApp> apps = FirebaseApp.getApps();
                if (!apps.isEmpty()) {
                    app = apps.get(0);
                } else {
                    Path resolvedPath = resolveCredentialsPath();
                    if (resolvedPath == null) {
                        logger.warn("Firebase credentials file not found. Set FIREBASE_CREDENTIALS_PATH or firebase.credentials.path.");
                        return;
                    }

                    try (InputStream serviceAccountStream = Files.newInputStream(resolvedPath)) {
                        GoogleCredentials credentials = GoogleCredentials.fromStream(serviceAccountStream);
                        FirebaseOptions.Builder optionsBuilder = FirebaseOptions.builder().setCredentials(credentials);

                        if (projectId != null && !projectId.isBlank()) {
                            optionsBuilder.setProjectId(projectId.trim());
                        }

                        app = FirebaseApp.initializeApp(optionsBuilder.build());
                        logger.info("Firebase initialized for auth/profile sync using credentials file at {}", resolvedPath);
                    }
                }

                firebaseAuth = FirebaseAuth.getInstance(app);
                firestore = FirestoreClient.getFirestore(app, resolvedDatabaseId());
                logger.info("Using Firestore database '{}' and users collection '{}'", resolvedDatabaseId(), usersCollection);
            } catch (Exception ex) {
                logger.warn("Unable to initialize Firebase for auth/profile sync.", ex);
            }
        }
    }

    private String resolvedDatabaseId() {
        if (databaseId == null || databaseId.isBlank()) {
            return "(default)";
        }
        return databaseId.trim();
    }

    private Path resolveCredentialsPath() {
        List<Path> candidates = new ArrayList<>();

        if (credentialsPath != null && !credentialsPath.isBlank()) {
            candidates.add(Paths.get(credentialsPath));
        }

        candidates.add(Paths.get("..", "..", "Firebase", "serviceAccountKey.json"));
        candidates.add(Paths.get("..", "Firebase", "serviceAccountKey.json"));
        candidates.add(Paths.get("serviceAccountKey.json"));

        for (Path candidate : candidates) {
            Path normalized = candidate.toAbsolutePath().normalize();
            if (Files.exists(normalized) && Files.isRegularFile(normalized)) {
                return normalized;
            }
        }

        return null;
    }

    private String resolveProvider(FirebaseToken decoded) {
        Object firebaseClaim = decoded.getClaims().get("firebase");
        if (!(firebaseClaim instanceof Map<?, ?> firebaseMap)) {
            return "unknown";
        }

        Object provider = firebaseMap.get("sign_in_provider");
        return provider == null ? "unknown" : String.valueOf(provider).toLowerCase(Locale.ROOT);
    }

    private String defaultDisplayName(String uid, String email) {
        if (email != null && email.contains("@")) {
            return email.substring(0, email.indexOf('@'));
        }

        if (uid == null || uid.isBlank()) {
            return "User";
        }

        int suffixLength = Math.min(6, uid.length());
        return "user-" + uid.substring(0, suffixLength);
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private String readClaimAsText(FirebaseToken token, String claimKey) {
        Object value = token.getClaims().get(claimKey);
        if (value == null) {
            return "";
        }
        return String.valueOf(value).trim();
    }

    private List<WatchlistItem> normalizeWatchlist(List<WatchlistItem> requestedItems) {
        if (requestedItems == null || requestedItems.isEmpty()) {
            return Collections.emptyList();
        }

        Map<String, WatchlistItem> bySymbol = new LinkedHashMap<>();
        for (WatchlistItem item : requestedItems) {
            if (item == null) {
                continue;
            }

            String symbol = firstNonBlank(item.getSymbol(), "").toUpperCase(Locale.ROOT);
            if (symbol.isBlank()) {
                continue;
            }

            String name = firstNonBlank(item.getName(), symbol);
            String image = firstNonBlank(item.getImage(), "");
            bySymbol.put(symbol, new WatchlistItem(symbol, name, image));
        }

        return new ArrayList<>(bySymbol.values());
    }

    private List<Map<String, Object>> watchlistToFirestore(List<WatchlistItem> normalized) {
        List<Map<String, Object>> payload = new ArrayList<>();
        for (WatchlistItem item : normalized) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("symbol", firstNonBlank(item.getSymbol(), "").toUpperCase(Locale.ROOT));
            entry.put("name", firstNonBlank(item.getName(), ""));
            entry.put("image", firstNonBlank(item.getImage(), ""));
            payload.add(entry);
        }
        return payload;
    }

    private List<WatchlistItem> readWatchlist(DocumentSnapshot userDoc) {
        if (userDoc == null || !userDoc.exists()) {
            return Collections.emptyList();
        }

        Object rawWatchlist = userDoc.get("watchlist");
        if (!(rawWatchlist instanceof List<?> rawList)) {
            return Collections.emptyList();
        }

        List<WatchlistItem> parsed = new ArrayList<>();
        for (Object entry : rawList) {
            if (!(entry instanceof Map<?, ?> rawMap)) {
                continue;
            }

            String symbol = firstNonBlank(
                    readMapString(rawMap, "symbol", "SYMBOL"),
                    ""
            ).toUpperCase(Locale.ROOT);

            if (symbol.isBlank()) {
                continue;
            }

            String name = firstNonBlank(readMapString(rawMap, "name", "NAME"), symbol);
            String image = firstNonBlank(readMapString(rawMap, "image", "IMAGE"), "");
            parsed.add(new WatchlistItem(symbol, name, image));
        }

        return normalizeWatchlist(parsed);
    }

    private String readMapString(Map<?, ?> map, String... keys) {
        for (String key : keys) {
            Object value = map.get(key);
            if (value != null) {
                String text = String.valueOf(value).trim();
                if (!text.isBlank()) {
                    return text;
                }
            }
        }
        return "";
    }
}

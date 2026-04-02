package com.example.crypto;

import com.google.api.core.ApiFuture;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.google.cloud.firestore.QuerySnapshot;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
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
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

@Service
public class LocationService {

    private static final Logger logger = LoggerFactory.getLogger(LocationService.class);

    private final String credentialsPath;
    private final String projectId;
    private final String collectionName;
    private final String databaseId;

    private volatile Firestore firestore;
    private volatile boolean initializationAttempted;

    public LocationService(@Value("${firebase.credentials.path:}") String credentialsPath,
                           @Value("${firebase.project-id:}") String projectId,
                           @Value("${firebase.firestore.locations-collection:locations}") String collectionName,
                           @Value("${firebase.firestore.database-id:(default)}") String databaseId) {
        this.credentialsPath = credentialsPath;
        this.projectId = projectId;
        this.collectionName = collectionName;
        this.databaseId = databaseId;
    }

    public List<LocationInfo> getLocations(String city, String type, String query) {
        List<LocationInfo> source = getLocationsFromFirestore();

        String cityFilter = normalizeFilter(city);
        String typeFilter = normalizeFilter(type);
        String textFilter = normalizeFilter(query);

        List<LocationInfo> filtered = new ArrayList<>();
        for (LocationInfo location : source) {
            boolean cityMatches = cityFilter.isEmpty() || location.getCity().equalsIgnoreCase(cityFilter);
            boolean typeMatches = typeFilter.isEmpty() || location.getType().equalsIgnoreCase(typeFilter);
            boolean textMatches = textFilter.isEmpty() || buildSearchText(location).contains(textFilter);

            if (cityMatches && typeMatches && textMatches) {
                filtered.add(location);
            }
        }

        filtered.sort(
                Comparator.comparing(LocationInfo::getCity, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(LocationInfo::getName, String.CASE_INSENSITIVE_ORDER)
        );

        return filtered;
    }

    private List<LocationInfo> getLocationsFromFirestore() {
        Firestore db = getFirestore();
        if (db == null) {
            return Collections.emptyList();
        }

        try {
            ApiFuture<QuerySnapshot> future = db.collection(collectionName).get();
            QuerySnapshot snapshot = future.get(10, TimeUnit.SECONDS);

            List<LocationInfo> locations = new ArrayList<>();
            for (QueryDocumentSnapshot document : snapshot.getDocuments()) {
                locations.add(toLocation(document));
            }

            logger.info("Loaded {} location documents from Firestore collection '{}' database '{}'", locations.size(), collectionName, resolvedDatabaseId());

            return locations;
        } catch (Exception ex) {
            logger.warn("Failed to read locations from Firestore collection '{}' database '{}'. Returning empty list.", collectionName, resolvedDatabaseId(), ex);
            return Collections.emptyList();
        }
    }

    private Firestore getFirestore() {
        if (firestore != null) {
            return firestore;
        }

        if (initializationAttempted) {
            return null;
        }

        synchronized (this) {
            if (firestore != null) {
                return firestore;
            }
            if (initializationAttempted) {
                return null;
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
                        return null;
                    }

                    try (InputStream serviceAccountStream = Files.newInputStream(resolvedPath)) {
                        GoogleCredentials credentials = GoogleCredentials.fromStream(serviceAccountStream);
                        FirebaseOptions.Builder optionsBuilder = FirebaseOptions.builder().setCredentials(credentials);

                        if (projectId != null && !projectId.isBlank()) {
                            optionsBuilder.setProjectId(projectId.trim());
                        }

                        app = FirebaseApp.initializeApp(optionsBuilder.build());
                        logger.info("Firebase initialized using credentials file at {}", resolvedPath);
                    }
                }

                firestore = FirestoreClient.getFirestore(app, resolvedDatabaseId());
                logger.info("Using Firestore database '{}' and collection '{}'", resolvedDatabaseId(), collectionName);
                return firestore;
            } catch (Exception ex) {
                logger.warn("Unable to initialize Firebase Firestore database '{}'.", resolvedDatabaseId(), ex);
                return null;
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

    private LocationInfo toLocation(QueryDocumentSnapshot document) {
        String id = nonBlankOrDefault(readFirstNonBlank(document, "id", "ID"), document.getId());

        // Support both conventional keys and custom Firestore keys from the uploaded dataset.
        String name = nonBlankOrDefault(readFirstNonBlank(document, "name", "NAME", "\uFEFFNAME"), "Unknown Location");
        String rawType = readFirstNonBlank(document, "type", "TYPE");
        String type = normalizeType(nonBlankOrDefault(rawType, "office"));
        String city = nonBlankOrDefault(readFirstNonBlank(document, "city", "CITY", "area", "AREA"), "Unknown City");
        String country = nonBlankOrDefault(readFirstNonBlank(document, "country", "COUNTRY"), "Canada");
        String address = nonBlankOrDefault(readFirstNonBlank(document, "address", "ADDRESS", "FULL ADDRESS", "full_address", "fullAddress"), "Address not available");
        String hours = nonBlankOrDefault(readFirstNonBlank(document, "hours", "HOURS", "time", "TIME"), "Hours not provided");
        String status = normalizeStatus(nonBlankOrDefault(readFirstNonBlank(document, "status", "STATUS"), deriveStatusFromHours(hours)));
        String phone = nonBlankOrDefault(readFirstNonBlank(document, "number", "NUMBER", "phone", "PHONE"), "N/A");
        String rating = nonBlankOrDefault(readFirstNonBlank(document, "rating", "RATING"), "N/A");

        List<String> services = readServices(document.get("services"));
        if (services.isEmpty() && rawType != null && !rawType.isBlank()) {
            services = List.of(rawType.trim());
        }

        Double latitude = readDouble(document.get("lat"));
        Double longitude = readDouble(document.get("lng"));

        return new LocationInfo(
                id,
                name,
                type,
                city,
                country,
                address,
                hours,
                status,
                phone,
                rating,
                services,
                latitude,
                longitude
        );
    }

    private String readFirstNonBlank(QueryDocumentSnapshot document, String... keys) {
        for (String key : keys) {
            String value = readString(document.get(key));
            if (!value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private List<String> readServices(Object value) {
        if (value == null) {
            return Collections.emptyList();
        }

        List<String> services = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                String asText = readString(item);
                if (!asText.isBlank()) {
                    services.add(asText);
                }
            }
            return services;
        }

        String single = readString(value);
        if (!single.isBlank()) {
            services.add(single);
        }
        return services;
    }

    private String buildSearchText(LocationInfo location) {
        return (location.getName() + " "
                + location.getAddress() + " "
                + location.getCity() + " "
                + location.getCountry() + " "
                + location.getPhone()).toLowerCase(Locale.ROOT);
    }

    private String normalizeType(String type) {
        String normalized = type == null ? "" : type.trim().toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) {
            return "office";
        }

        if (normalized.contains("atm")) {
            return "atm";
        }
        if (normalized.contains("exchange")) {
            return "exchange";
        }
        if (normalized.contains("merchant") || normalized.contains("shop") || normalized.contains("store") || normalized.contains("market")) {
            return "merchant";
        }
        if (normalized.contains("office")) {
            return "office";
        }

        return switch (normalized) {
            case "atm", "exchange", "merchant", "office" -> normalized;
            default -> "office";
        };
    }

    private String normalizeStatus(String status) {
        String normalized = status == null ? "" : status.trim().toLowerCase(Locale.ROOT);
        return "closed".equals(normalized) ? "closed" : "open";
    }

    private String deriveStatusFromHours(String hours) {
        String normalized = hours == null ? "" : hours.trim().toLowerCase(Locale.ROOT);
        return normalized.contains("closed") ? "closed" : "open";
    }

    private String normalizeFilter(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private String readString(Object value) {
        return value == null ? "" : value.toString().trim();
    }

    private String nonBlankOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private Double readDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return null;
    }
}

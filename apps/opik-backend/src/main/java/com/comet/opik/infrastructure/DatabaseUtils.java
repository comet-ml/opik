package com.comet.opik.infrastructure;

import io.dropwizard.db.DataSourceFactory;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;
import java.util.stream.Collectors;

@UtilityClass
@Slf4j
public class DatabaseUtils {

    public static final int ANALYTICS_DELETE_BATCH_SIZE = 10000;

    public static DataSourceFactory filterProperties(DataSourceFactory dataSourceFactory) {
        var filteredProperties = dataSourceFactory.getProperties()
                .entrySet()
                .stream()
                .filter(entry -> StringUtils.isNotBlank(entry.getValue()))
                .collect(Collectors.toMap(
                        java.util.Map.Entry::getKey,
                        java.util.Map.Entry::getValue));
        dataSourceFactory.setProperties(filteredProperties);

        return dataSourceFactory;
    }

    /**
     * Calculate placeholder hash for version identification.
     * TODO OPIK-3015: Replace with actual content-based hash from dataset items.
     *
     * @param datasetId the dataset identifier
     * @return a hex string hash (first 16 characters of SHA-256)
     */
    public static String calculatePlaceholderVersionHash(UUID datasetId) {
        try {
            // Use timestamp + dataset ID for unique hash per commit
            String input = datasetId.toString() + ":" + System.currentTimeMillis();
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));

            // Convert to hex string (first 16 chars for display)
            StringBuilder hexString = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                String hex = Integer.toHexString(0xff & hashBytes[i]);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }

            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }
}

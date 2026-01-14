package com.comet.opik.utils;

import lombok.experimental.UtilityClass;

import java.util.UUID;

/**
 * Utility class for filename operations, particularly for Content-Disposition headers.
 */
@UtilityClass
public class FileNameUtils {

    private static final int MAX_FILENAME_LENGTH = 100;

    /**
     * Builds a safe filename for Content-Disposition header.
     * Truncates to a safe length and provides a fallback for null/empty names.
     *
     * @param name      The base name (may be null)
     * @param extension The file extension (e.g., ".csv")
     * @param fallbackId The ID to use as fallback if name is null/empty
     * @param fallbackPrefix The prefix for fallback filename (e.g., "dataset-export-")
     * @return A safe filename with the specified extension
     */
    public static String buildSafeFilename(String name, String extension, UUID fallbackId, String fallbackPrefix) {
        if (name == null || name.isBlank()) {
            return fallbackPrefix + fallbackId + extension;
        }

        String safeName = name.trim();

        // Truncate to safe max length
        if (safeName.length() > MAX_FILENAME_LENGTH) {
            safeName = safeName.substring(0, MAX_FILENAME_LENGTH);
        }

        return safeName + extension;
    }

    /**
     * Builds a safe CSV filename for dataset exports.
     *
     * @param datasetName The dataset name (may be null)
     * @param jobId       The job ID to use as fallback
     * @return A safe filename ending with .csv
     */
    public static String buildDatasetExportFilename(String datasetName, UUID jobId) {
        return buildSafeFilename(datasetName, ".csv", jobId, "dataset-export-");
    }
}

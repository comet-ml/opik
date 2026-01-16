package com.comet.opik.utils;

import com.google.common.base.CharMatcher;
import lombok.experimental.UtilityClass;

import java.util.UUID;

/**
 * Utility class for filename operations, particularly for Content-Disposition headers.
 */
@UtilityClass
public class FileNameUtils {

    private static final int MAX_FILENAME_LENGTH = 100;

    /**
     * CharMatcher that matches characters unsafe for HTTP headers.
     * Includes ISO control characters (CR, LF, tab, etc.), double quotes, and backslashes.
     */
    private static final CharMatcher UNSAFE_HEADER_CHARS = CharMatcher.javaIsoControl()
            .or(CharMatcher.anyOf("\"\\"));

    /**
     * Sanitizes a filename by removing characters that could cause header injection.
     * Uses Guava's CharMatcher to remove ISO control characters (CR, LF, tab, etc.),
     * double quotes, and backslashes.
     *
     * @param name The filename to sanitize
     * @return The sanitized filename, or null if input was null
     */
    private static String sanitizeFilename(String name) {
        if (name == null) {
            return null;
        }
        return UNSAFE_HEADER_CHARS.removeFrom(name);
    }

    /**
     * Builds a safe filename for Content-Disposition header.
     * Removes control characters (CR/LF/tab), quotes, and backslashes to prevent header injection.
     * Truncates to a safe length and provides a fallback for null/empty names.
     *
     * @param name           The base name (may be null)
     * @param extension      The file extension (e.g., ".csv")
     * @param fallbackId     The ID to use as fallback if name is null/empty
     * @param fallbackPrefix The prefix for fallback filename (e.g., "dataset-export-")
     * @return A safe filename with the specified extension
     */
    public static String buildSafeFilename(String name, String extension, UUID fallbackId, String fallbackPrefix) {
        if (name == null || name.isBlank()) {
            return fallbackPrefix + fallbackId + extension;
        }

        // Sanitize: remove control characters, quotes, and backslashes to prevent header injection
        String safeName = sanitizeFilename(name).trim();

        // If sanitization resulted in empty string, use fallback
        if (safeName.isBlank()) {
            return fallbackPrefix + fallbackId + extension;
        }

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

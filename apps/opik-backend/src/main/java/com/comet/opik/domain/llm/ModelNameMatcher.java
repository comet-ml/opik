package com.comet.opik.domain.llm;

import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Utility for matching model names with normalization and suffix matching.
 * Handles provider prefixes, case sensitivity, and name variations.
 *
 * This class provides shared functionality for matching model names across
 * different contexts (cost calculation, capability detection, etc.)
 */
public class ModelNameMatcher {

    private ModelNameMatcher() {
        // Utility class - prevent instantiation
    }

    /**
     * Normalize a model name: trim and lowercase for case-insensitive matching.
     *
     * @param modelName The model name to normalize
     * @return Normalized model name (trimmed and lowercased)
     */
    public static String normalize(String modelName) {
        return modelName == null ? "" : modelName.trim().toLowerCase();
    }

    /**
     * Generate candidate keys for model name matching.
     * Handles variations like:
     * - Full model name
     * - Model name without prefixes (after each /)
     * - Model name without colon suffixes (:free, :extended, etc.)
     *
     * Examples:
     * - "openrouter/qwen/model:free" generates:
     *   ["openrouter/qwen/model:free", "qwen/model:free", "model:free",
     *    "openrouter/qwen/model", "qwen/model", "model"]
     *
     * @param modelName The model name to generate candidates for
     * @return List of candidate keys in priority order (most specific first)
     */
    public static List<String> generateCandidateKeys(String modelName) {
        if (StringUtils.isBlank(modelName)) {
            return Collections.emptyList();
        }

        var candidates = new LinkedHashSet<String>(); // Preserves order, prevents duplicates
        var normalized = normalize(modelName);
        candidates.add(normalized);

        // Generate all slash-separated suffix variations
        // "a/b/c" -> ["a/b/c", "b/c", "c"]
        var parts = normalized.split("/");
        for (int i = 1; i < parts.length; i++) {
            var suffix = String.join("/", Arrays.copyOfRange(parts, i, parts.length));
            candidates.add(suffix);
        }

        // Handle colon suffixes (e.g., "model:free" -> "model")
        var colonIndex = normalized.indexOf(':');
        if (colonIndex > 0) {
            var withoutColon = normalized.substring(0, colonIndex);
            candidates.add(withoutColon);

            // Also add slash variations without colon
            var partsNoColon = withoutColon.split("/");
            for (int i = 1; i < partsNoColon.length; i++) {
                var suffix = String.join("/", Arrays.copyOfRange(partsNoColon, i, partsNoColon.length));
                candidates.add(suffix);
            }
        }

        return new ArrayList<>(candidates);
    }

    /**
     * Find a value in a map using sophisticated matching:
     * 1. Try exact matches with candidate keys (fast path)
     * 2. Try suffix matching against stored keys (fallback for provider prefix variations)
     *
     * This handles cases like:
     * - "qwen/model" matching "deepinfra/Qwen/Model" (after normalization)
     * - "model:free" matching "provider/model"
     *
     * @param map The map to search in (with normalized keys)
     * @param modelName The model name to search for
     * @param <V> The type of values in the map
     * @return Optional of the found value, or empty if not found
     */
    public static <V> Optional<V> findInMap(Map<String, V> map, String modelName) {
        if (StringUtils.isBlank(modelName) || map.isEmpty()) {
            return Optional.empty();
        }

        var candidates = generateCandidateKeys(modelName);

        // First pass: try exact matches (O(n) where n = number of candidates, typically ~5-10)
        for (var candidate : candidates) {
            var found = map.get(candidate);
            if (found != null) {
                return Optional.of(found);
            }
        }

        // Second pass: try suffix matching against stored keys
        // This handles cases like "qwen/model" matching "deepinfra/qwen/model"
        // Note: This is O(n*m) where m = map size - consider building a reverse index if performance is critical
        for (var candidate : candidates) {
            for (var entry : map.entrySet()) {
                var storedKey = entry.getKey();
                // Check if the stored key ends with the candidate (with a slash separator)
                if (storedKey.endsWith("/" + candidate) || storedKey.equals(candidate)) {
                    return Optional.of(entry.getValue());
                }
            }
        }

        return Optional.empty();
    }
}

package com.comet.opik.domain.llm;

import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
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
     * Build a reverse suffix index for efficient model name lookups.
     * Maps all possible suffixes of stored keys back to their full keys.
     *
     * Example:
     * - Key "openrouter/qwen/model" generates mappings:
     *   "openrouter/qwen/model" -> "openrouter/qwen/model"
     *   "qwen/model" -> "openrouter/qwen/model"
     *   "model" -> "openrouter/qwen/model"
     *
     * @param map The map with normalized keys to build index from
     * @return Suffix index mapping suffixes to full keys
     */
    public static Map<String, String> buildSuffixIndex(Map<String, ?> map) {
        var index = new HashMap<String, String>();

        for (String fullKey : map.keySet()) {
            // 1. Always index the full key first
            index.putIfAbsent(fullKey, fullKey);

            // 2. Find all suffixes starting after a '/'
            int currentSeparatorIndex = -1;
            while ((currentSeparatorIndex = fullKey.indexOf('/', currentSeparatorIndex + 1)) != -1) {
                // The suffix starts one character *after* the current separator
                String suffix = fullKey.substring(currentSeparatorIndex + 1);

                // First match wins: putIfAbsent ensures that the first fullKey
                // to claim this suffix is the one that remains mapped
                index.putIfAbsent(suffix, fullKey);
            }
        }

        return Collections.unmodifiableMap(index);
    }

    /**
     * Find a value in a map using sophisticated matching with a pre-built suffix index.
     * This is the optimized version that avoids O(n*m) iteration.
     *
     * Performance: O(n) where n = number of candidates (~5-10)
     *
     * @param map The map to search in (with normalized keys)
     * @param modelName The model name to search for
     * @param suffixIndex Pre-built suffix index from buildSuffixIndex()
     * @param <V> The type of values in the map
     * @return Optional of the found value, or empty if not found
     */
    public static <V> Optional<V> findInMap(Map<String, V> map, String modelName, Map<String, String> suffixIndex) {
        if (StringUtils.isBlank(modelName) || map.isEmpty()) {
            return Optional.empty();
        }

        var candidates = generateCandidateKeys(modelName);

        // First pass: Try exact matches for all candidates
        // This ensures we prioritize exact matches over suffix matches
        for (var candidate : candidates) {
            var exactMatch = map.get(candidate);
            if (exactMatch != null) {
                return Optional.of(exactMatch);
            }
        }

        // Second pass: Try suffix index matches
        // Only do this if no exact match was found
        for (var candidate : candidates) {
            String fullKey = suffixIndex.get(candidate);
            if (fullKey != null && !fullKey.equals(candidate)) {
                // Only use suffix index result if it points to a different key
                var value = map.get(fullKey);
                if (value != null) {
                    return Optional.of(value);
                }
            }
        }

        return Optional.empty();
    }

}

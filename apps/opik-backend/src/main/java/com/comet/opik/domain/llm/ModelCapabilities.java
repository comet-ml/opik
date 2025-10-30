package com.comet.opik.domain.llm;

import com.comet.opik.api.ModelCostData;
import com.comet.opik.domain.cost.CostService;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.experimental.UtilityClass;
import org.apache.commons.lang3.StringUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

@UtilityClass
public class ModelCapabilities {

    // Hardcoded vision-capable models or patterns that should support vision
    // This list overrides the JSON configuration to handle cases where:
    // - The JSON uses different naming conventions (e.g., openrouter/ prefix)
    // - Models are missing from the JSON
    // - The JSON is not yet updated with new vision models
    private static final Set<Pattern> VISION_MODEL_PATTERNS = Set.of(
        // Made pattern more flexible to match anywhere in the name
        Pattern.compile(".*qwen.*vl.*", Pattern.CASE_INSENSITIVE)
    );

    private static final Map<String, ModelCapability> CAPABILITIES_BY_NORMALIZED_NAME = loadCapabilities();

    /**
     * Checks if a model name matches any of the hardcoded vision patterns.
     */
    private boolean matchesVisionPattern(String modelName) {
        return VISION_MODEL_PATTERNS.stream().anyMatch(pattern -> pattern.matcher(modelName).matches());
    }    

    public boolean supportsVision(String modelName) {
        return find(modelName).map(ModelCapability::supportsVision).orElse(false);
    }

    private Optional<ModelCapability> find(String modelName) {
        if (StringUtils.isBlank(modelName)) {
            return Optional.empty();
        }

        var searchCandidates = candidateKeys(modelName);

        // First pass: try exact matches
        for (var candidate : searchCandidates) {
            var found = CAPABILITIES_BY_NORMALIZED_NAME.get(candidate);
            if (found != null) {
                return Optional.of(found);
            }
        }

        // Second pass: try matching against the suffix of stored model names
        // This handles cases like "qwen/qwen2.5-vl-32b-instruct" matching "deepinfra/Qwen/Qwen2.5-VL-32B-Instruct"
        for (var candidate : searchCandidates) {
            for (var entry : CAPABILITIES_BY_NORMALIZED_NAME.entrySet()) {
                var normalizedStoredKey = entry.getKey();
                // Check if the stored key ends with the candidate
                if (normalizedStoredKey.endsWith("/" + candidate) || normalizedStoredKey.equals(candidate)) {
                    return Optional.of(entry.getValue());
                }
            }
        }

        return Optional.empty();
    }

    private Map<String, ModelCapability> loadCapabilities() {
        try {
            Map<String, ModelCostData> rawData = JsonUtils.readJsonFile(CostService.MODEL_PRICES_FILE,
                    new TypeReference<>() {
                    });
            if (rawData.isEmpty()) {
                throw new IllegalStateException(
                        "No entries found in model prices file '%s'".formatted(CostService.MODEL_PRICES_FILE));
            }

            var capabilities = new HashMap<String, ModelCapability>();
            rawData.forEach((modelName, modelData) -> {
                if (StringUtils.isBlank(modelName)) {
                    return;
                }

                var normalizedName = normalize(modelName);
                var canonicalName = modelName.trim();

                // Check if model matches vision patterns - override JSON if it does
                boolean supportsVision = modelData.supportsVision() || matchesVisionPattern(canonicalName);


                capabilities.putIfAbsent(normalizedName, ModelCapability.builder()
                        .name(canonicalName)
                        .litellmProvider(Objects.requireNonNullElse(modelData.litellmProvider(), ""))
                        .supportsVision(supportsVision)
                        .build());
            });

            return Collections.unmodifiableMap(capabilities);
        } catch (IOException exception) {
            throw new UncheckedIOException(
                    "Failed to load model capabilities from file '%s'".formatted(CostService.MODEL_PRICES_FILE),
                    exception);
        }
    }

    private List<String> candidateKeys(String modelName) {
        var candidates = new HashSet<String>();
        var normalized = normalize(modelName);
        candidates.add(normalized);

        var slashIndex = normalized.lastIndexOf('/') + 1;
        if (slashIndex > 0 && slashIndex < normalized.length()) {
            candidates.add(normalized.substring(slashIndex));
        }

        var colonIndex = normalized.indexOf(':');
        if (colonIndex > 0) {
            candidates.add(normalized.substring(0, colonIndex));

            if (slashIndex > 0 && slashIndex < colonIndex) {
                candidates.add(normalized.substring(slashIndex, colonIndex));
            }
        }

        return new ArrayList<>(candidates);
    }

    private String normalize(String modelName) {
        return modelName.trim().toLowerCase();
    }
}

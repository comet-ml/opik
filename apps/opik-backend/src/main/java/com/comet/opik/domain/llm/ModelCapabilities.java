package com.comet.opik.domain.llm;

import com.comet.opik.api.ModelCostData;
import com.comet.opik.domain.cost.CostService;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.experimental.UtilityClass;
import org.apache.commons.lang3.StringUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Collections;
import java.util.HashMap;
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
            Pattern.compile(".*qwen.*vl.*", Pattern.CASE_INSENSITIVE));

    private static final Map<String, ModelCapability> CAPABILITIES_BY_CANONICAL_KEY = loadCapabilities();

    /**
     * Checks if a model name matches any of the hardcoded vision patterns.
     */
    private boolean matchesVisionPattern(String modelName) {
        if (StringUtils.isBlank(modelName)) {
            return false;
        }
        return VISION_MODEL_PATTERNS.stream().anyMatch(pattern -> pattern.matcher(modelName).matches());
    }

    public boolean supportsVision(String modelName) {
        // First try to find in the capabilities map
        var capability = find(modelName);
        if (capability.isPresent()) {
            return capability.get().supportsVision();
        }

        // Fallback: Check if model matches vision patterns (for models not in JSON)
        return matchesVisionPattern(modelName);
    }

    private Optional<ModelCapability> find(String modelName) {
        // Resolve to canonical JSON key using explicit enum mappings
        String canonicalKey = ModelNameMapper.resolveCanonicalKey(modelName);
        // Normalize the canonical key for case-insensitive lookup
        String normalizedKey = ModelNameMapper.normalize(canonicalKey);
        return Optional.ofNullable(CAPABILITIES_BY_CANONICAL_KEY.get(normalizedKey));
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

                // Use canonical key from JSON (preserve casing)
                var canonicalKey = modelName.trim();
                var normalizedKey = ModelNameMapper.normalize(canonicalKey);

                // Check if model matches vision patterns - override JSON if it does
                boolean supportsVision = modelData.supportsVision() || matchesVisionPattern(normalizedKey);

                // Store by normalized key for case-insensitive lookups
                capabilities.putIfAbsent(normalizedKey, ModelCapability.builder()
                        .name(canonicalKey)
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

}

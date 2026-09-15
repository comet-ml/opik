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

    // Hardcoded vision-capable models or patterns that should support vision/multimodal
    // This set of patterns overrides the JSON configuration to handle cases where:
    // - The JSON uses different naming conventions (e.g., openrouter/ prefix)
    // - Models are missing from the JSON
    // - The JSON is not yet updated with new vision models
    // - Custom LLM models that support multimodal (audio, video, images)
    private static final Set<Pattern> VISION_MODEL_PATTERNS = Set.of(
            // Made pattern more flexible to match anywhere in the name
            Pattern.compile(".*qwen.*vl.*", Pattern.CASE_INSENSITIVE),
            // Qwen Omni models support multimodal (audio, video, images)
            Pattern.compile(".*qwen.*omni.*", Pattern.CASE_INSENSITIVE));

    /**
     * Anthropic's Claude models reject temperature and top_p together. The family name is the only
     * signal common to every way they are routed — Anthropic's own ids ({@code claude-opus-4-6}),
     * Bedrock's decorated ids ({@code us.anthropic.claude-…-v1:0}) and OpenAI-compatible proxy names
     * — so the match is deliberately loose. A false positive only drops top_p when temperature is
     * also set, which is what Anthropic recommends regardless.
     */
    private static final Pattern EXCLUSIVE_SAMPLING_MODEL_PATTERN = Pattern.compile(".*claude.*",
            Pattern.CASE_INSENSITIVE);

    private static final Map<String, ModelCapability> CAPABILITIES_BY_NORMALIZED_NAME = loadCapabilities();

    /**
     * Checks if a model name matches any of the hardcoded vision patterns.
     */
    private boolean matchesVisionPattern(String modelName) {
        if (StringUtils.isBlank(modelName)) {
            return false;
        }
        return VISION_MODEL_PATTERNS.stream().anyMatch(pattern -> pattern.matcher(modelName).matches());
    }

    /**
     * Whether the model rejects temperature and top_p in the same request, so that only one may be sent.
     */
    public boolean requiresExclusiveSamplingParams(String modelName) {
        if (StringUtils.isBlank(modelName)) {
            return false;
        }
        // Match the model, not the route to it. Custom ids carry the gateway in the prefix
        // (custom-llm/<provider_name>/<model>), so a provider someone called "claude-gw" would
        // otherwise make every model behind it — Mistral, Llama — look like Claude and lose its top_p.
        // An id with no separator is the model; one that ends in a separator names no model at all,
        // and must not fall back to the gateway — the frontend reads the same id the same way.
        var model = StringUtils.contains(modelName, "/")
                ? StringUtils.substringAfterLast(modelName, "/")
                : modelName;
        return EXCLUSIVE_SAMPLING_MODEL_PATTERN.matcher(model).matches();
    }

    public boolean supportsVision(String modelName) {
        if (matchesVisionPattern(modelName)) {
            return true;
        }

        return find(modelName).map(ModelCapability::supportsVision).orElse(false);
    }

    private Optional<ModelCapability> find(String modelName) {
        if (StringUtils.isBlank(modelName)) {
            return Optional.empty();
        }

        for (var candidate : candidateKeys(modelName)) {
            var found = CAPABILITIES_BY_NORMALIZED_NAME.get(candidate);
            if (found != null) {
                return Optional.of(found);
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
                capabilities.putIfAbsent(normalizedName, ModelCapability.builder()
                        .name(canonicalName)
                        .litellmProvider(Objects.requireNonNullElse(modelData.litellmProvider(), ""))
                        .supportsVision(modelData.supportsVision())
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

    /**
     * Normalizes model names for consistent lookup.
     * - Converts to lowercase
     * - Trims whitespace
     * - Replaces dots with hyphens (fixes issue #4114 for vision capability detection)
     *
     * This ensures "claude-3.5-sonnet" matches "claude-3-5-sonnet" in the pricing database.
     */
    private String normalize(String modelName) {
        return modelName.trim().toLowerCase().replace('.', '-');
    }
}

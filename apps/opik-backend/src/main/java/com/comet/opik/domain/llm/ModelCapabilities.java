package com.comet.opik.domain.llm;

import com.comet.opik.api.ModelCostData;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Slf4j
public final class ModelCapabilities {

    private static final String PRICES_FILE = "model_prices_and_context_window.json";
    private static final Map<String, ModelCapability> CAPABILITIES_BY_NAME = loadCapabilities();
    private static final Map<String, ModelCapability> CAPABILITIES_BY_NORMALIZED_NAME = buildNormalizedIndex(
            CAPABILITIES_BY_NAME);

    private ModelCapabilities() {
    }

    public static Map<String, ModelCapability> capabilities() {
        return CAPABILITIES_BY_NAME;
    }

    public static Optional<ModelCapability> find(String modelName) {
        if (modelName == null || modelName.isBlank()) {
            return Optional.empty();
        }

        for (String candidate : candidateKeys(modelName)) {
            ModelCapability match = CAPABILITIES_BY_NORMALIZED_NAME.get(candidate);
            if (match != null) {
                return Optional.of(match);
            }
        }

        return Optional.empty();
    }

    public static boolean supportsVision(String modelName) {
        return find(modelName).map(ModelCapability::supportsVision).orElse(false);
    }

    private static Map<String, ModelCapability> loadCapabilities() {
        try {
            Map<String, ModelCostData> rawData = JsonUtils.readJsonFile(PRICES_FILE, new TypeReference<>() {
            });
            if (rawData.isEmpty()) {
                log.warn("Model prices file '{}' did not contain any entries", PRICES_FILE);
                return Map.of();
            }

            Map<String, ModelCapability> capabilities = new HashMap<>();
            rawData.forEach((modelName, modelData) -> {
                if (modelName == null || modelName.isBlank()) {
                    return;
                }

                String canonicalName = modelName.trim();
                boolean supportsVision = Boolean.TRUE.equals(modelData.supportsVision());
                String provider = Optional.ofNullable(modelData.litellmProvider()).orElse("");

                capabilities.put(canonicalName, new ModelCapability(canonicalName, provider, supportsVision));
            });

            return Collections.unmodifiableMap(capabilities);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to load model capabilities from '" + PRICES_FILE + "'", exception);
        }
    }

    private static Map<String, ModelCapability> buildNormalizedIndex(
            @NonNull Map<String, ModelCapability> capabilities) {
        Map<String, ModelCapability> normalized = new HashMap<>();
        capabilities.forEach((name, capability) -> normalized.putIfAbsent(normalize(name), capability));
        return Collections.unmodifiableMap(normalized);
    }

    private static List<String> candidateKeys(@NonNull String modelName) {
        Set<String> candidates = new LinkedHashSet<>();
        String normalized = normalize(modelName);
        candidates.add(normalized);

        int slashIndex = normalized.lastIndexOf('/') + 1;
        if (slashIndex > 0 && slashIndex < normalized.length()) {
            candidates.add(normalized.substring(slashIndex));
        }

        int colonIndex = normalized.indexOf(':');
        if (colonIndex > 0) {
            candidates.add(normalized.substring(0, colonIndex));

            if (slashIndex > 0 && slashIndex < colonIndex) {
                candidates.add(normalized.substring(slashIndex, colonIndex));
            }
        }

        return new ArrayList<>(candidates);
    }

    private static String normalize(@NonNull String modelName) {
        return modelName.trim().toLowerCase(Locale.ENGLISH);
    }
}

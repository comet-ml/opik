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

@UtilityClass
public class ModelCapabilities {

    private static final Map<String, ModelCapability> CAPABILITIES_BY_NORMALIZED_NAME = loadCapabilities();

    public boolean supportsVision(String modelName) {
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

    private String normalize(String modelName) {
        return modelName.trim().toLowerCase();
    }
}

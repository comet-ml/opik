package com.comet.opik.infrastructure.llm.antropic;

import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.ws.rs.BadRequestException;
import lombok.experimental.UtilityClass;

import java.util.Map;
import java.util.Optional;

@UtilityClass
class AnthropicEffort {

    static final String OUTPUT_CONFIG = "output_config";
    static final String EFFORT = "effort";
    static final String FORMAT = "format";
    static final String THINKING = "thinking";
    static final String ADAPTIVE = "adaptive";

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    Optional<Map<?, ?>> toOutputConfig(String model, JsonNode customParameters) {
        if (customParameters == null || !customParameters.isObject()) {
            return Optional.empty();
        }
        return toOutputConfig(model, JsonUtils.getMapper().convertValue(customParameters, MAP_TYPE));
    }

    Optional<Map<String, Object>> toCustomParameters(String model, Map<String, Object> customParameters) {
        return toOutputConfig(model, customParameters).map(outputConfig -> impliesAdaptiveThinking(model,
                customParameters, outputConfig)
                        ? Map.of(OUTPUT_CONFIG, outputConfig, THINKING, Map.of("type", ADAPTIVE))
                        : Map.of(OUTPUT_CONFIG, outputConfig));
    }

    boolean impliesAdaptiveThinking(String model, JsonNode customParameters) {
        if (customParameters == null || !customParameters.isObject()) {
            return false;
        }
        return impliesAdaptiveThinking(model, JsonUtils.getMapper().convertValue(customParameters, MAP_TYPE));
    }

    boolean impliesAdaptiveThinking(String model, Map<String, Object> customParameters) {
        return toOutputConfig(model, customParameters)
                .map(outputConfig -> impliesAdaptiveThinking(model, customParameters, outputConfig))
                .orElse(false);
    }

    // Opik calls the effort control "Thinking effort", but on these models a level alone only shortens or lengthens the
    // answer: Anthropic runs a request that does not ask for thinking with none. A request that sets thinking itself,
    // disabled included, is left as it is.
    private boolean impliesAdaptiveThinking(String model, Map<String, Object> customParameters,
            Map<?, ?> outputConfig) {
        return AnthropicModelName.thinksOnlyWhenAsked(model)
                && customParameters.get(THINKING) == null
                && outputConfig.get(EFFORT) != null;
    }

    private Optional<Map<?, ?>> toOutputConfig(String model, Map<String, Object> customParameters) {
        if (customParameters == null || customParameters.get(OUTPUT_CONFIG) == null) {
            return Optional.empty();
        }
        if (!(customParameters.get(OUTPUT_CONFIG) instanceof Map<?, ?> outputConfig)) {
            throw new BadRequestException(
                    "custom_parameters.output_config must be an object, model '%s'".formatted(model));
        }
        validateEffort(model, outputConfig.get(EFFORT));
        return outputConfig.isEmpty() ? Optional.empty() : Optional.of(outputConfig);
    }

    private void validateEffort(String model, Object effort) {
        if (effort == null) {
            return;
        }
        if (!(effort instanceof String level)) {
            throw new BadRequestException(
                    "custom_parameters.output_config.effort must be a string, model '%s', effort '%s'"
                            .formatted(model, effort));
        }
        // A model without a row, such as one the model sync just added, is only checked for a known level name.
        // Anthropic has the final word on it, so a stale table never blocks a new model.
        var supported = AnthropicModelName.effortLevels(model).orElse(AnthropicModelName.ALL_EFFORT_LEVELS);
        if (supported.isEmpty()) {
            throw new BadRequestException(
                    "The model does not support custom_parameters.output_config.effort, model '%s', effort '%s'"
                            .formatted(model, level));
        }
        if (!supported.contains(level)) {
            throw new BadRequestException(
                    "Unsupported custom_parameters.output_config.effort for the model, model '%s', effort '%s', supported '%s'"
                            .formatted(model, level, supported));
        }
    }
}

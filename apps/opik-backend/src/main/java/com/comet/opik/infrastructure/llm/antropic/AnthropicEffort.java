package com.comet.opik.infrastructure.llm.antropic;

import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.ws.rs.BadRequestException;
import lombok.experimental.UtilityClass;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.comet.opik.infrastructure.llm.antropic.AnthropicModelName.CLAUDE_FABLE_5;
import static com.comet.opik.infrastructure.llm.antropic.AnthropicModelName.CLAUDE_FABLE_5_1;
import static com.comet.opik.infrastructure.llm.antropic.AnthropicModelName.CLAUDE_HAIKU_4_5;
import static com.comet.opik.infrastructure.llm.antropic.AnthropicModelName.CLAUDE_MYTHOS_PREVIEW;
import static com.comet.opik.infrastructure.llm.antropic.AnthropicModelName.CLAUDE_OPUS_4;
import static com.comet.opik.infrastructure.llm.antropic.AnthropicModelName.CLAUDE_OPUS_4_1;
import static com.comet.opik.infrastructure.llm.antropic.AnthropicModelName.CLAUDE_OPUS_4_5;
import static com.comet.opik.infrastructure.llm.antropic.AnthropicModelName.CLAUDE_OPUS_4_6;
import static com.comet.opik.infrastructure.llm.antropic.AnthropicModelName.CLAUDE_OPUS_4_6_20260205;
import static com.comet.opik.infrastructure.llm.antropic.AnthropicModelName.CLAUDE_OPUS_4_7;
import static com.comet.opik.infrastructure.llm.antropic.AnthropicModelName.CLAUDE_OPUS_4_7_20260416;
import static com.comet.opik.infrastructure.llm.antropic.AnthropicModelName.CLAUDE_OPUS_4_8;
import static com.comet.opik.infrastructure.llm.antropic.AnthropicModelName.CLAUDE_OPUS_5;
import static com.comet.opik.infrastructure.llm.antropic.AnthropicModelName.CLAUDE_OPUS_5_5;
import static com.comet.opik.infrastructure.llm.antropic.AnthropicModelName.CLAUDE_SONNET_3_7;
import static com.comet.opik.infrastructure.llm.antropic.AnthropicModelName.CLAUDE_SONNET_4;
import static com.comet.opik.infrastructure.llm.antropic.AnthropicModelName.CLAUDE_SONNET_4_5;
import static com.comet.opik.infrastructure.llm.antropic.AnthropicModelName.CLAUDE_SONNET_4_5_20250929;
import static com.comet.opik.infrastructure.llm.antropic.AnthropicModelName.CLAUDE_SONNET_4_6;
import static com.comet.opik.infrastructure.llm.antropic.AnthropicModelName.CLAUDE_SONNET_5;

@UtilityClass
class AnthropicEffort {

    static final String OUTPUT_CONFIG = "output_config";
    static final String EFFORT = "effort";

    private static final List<String> ALL_LEVELS = List.of("low", "medium", "high", "xhigh", "max");
    private static final List<String> LEVELS_WITHOUT_XHIGH = List.of("low", "medium", "high", "max");
    private static final List<String> LEVELS_UP_TO_HIGH = List.of("low", "medium", "high");

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    // Must match thinkingEffortOptions in ANTHROPIC_MODEL_CAPABILITIES (apps/opik-frontend/src/constants/llm.ts):
    // the playground only offers what this accepts. A model missing here is checked against ALL_LEVELS only and
    // Anthropic has the final word, so an unlisted new model is never blocked by a stale table.
    private static final Map<String, List<String>> LEVELS_BY_MODEL = Map.ofEntries(
            Map.entry(CLAUDE_OPUS_5_5.getValue(), ALL_LEVELS),
            Map.entry(CLAUDE_OPUS_5.getValue(), ALL_LEVELS),
            Map.entry(CLAUDE_OPUS_4_8.getValue(), ALL_LEVELS),
            Map.entry(CLAUDE_OPUS_4_7.getValue(), ALL_LEVELS),
            Map.entry(CLAUDE_OPUS_4_7_20260416.getValue(), ALL_LEVELS),
            Map.entry(CLAUDE_SONNET_5.getValue(), ALL_LEVELS),
            Map.entry(CLAUDE_FABLE_5.getValue(), ALL_LEVELS),
            Map.entry(CLAUDE_FABLE_5_1.getValue(), ALL_LEVELS),
            Map.entry(CLAUDE_OPUS_4_6.getValue(), LEVELS_WITHOUT_XHIGH),
            Map.entry(CLAUDE_OPUS_4_6_20260205.getValue(), LEVELS_WITHOUT_XHIGH),
            Map.entry(CLAUDE_MYTHOS_PREVIEW.getValue(), LEVELS_WITHOUT_XHIGH),
            Map.entry(CLAUDE_SONNET_4_6.getValue(), LEVELS_WITHOUT_XHIGH),
            Map.entry(CLAUDE_OPUS_4_5.getValue(), LEVELS_UP_TO_HIGH),
            Map.entry(CLAUDE_SONNET_3_7.getValue(), List.of()),
            Map.entry(CLAUDE_HAIKU_4_5.getValue(), List.of()),
            Map.entry(CLAUDE_OPUS_4.getValue(), List.of()),
            Map.entry(CLAUDE_OPUS_4_1.getValue(), List.of()),
            Map.entry(CLAUDE_SONNET_4.getValue(), List.of()),
            Map.entry(CLAUDE_SONNET_4_5.getValue(), List.of()),
            Map.entry(CLAUDE_SONNET_4_5_20250929.getValue(), List.of()));

    Optional<Map<String, Object>> toCustomParameters(String model, JsonNode customParameters) {
        if (customParameters == null || !customParameters.isObject()) {
            return Optional.empty();
        }
        return toCustomParameters(model, JsonUtils.getMapper().convertValue(customParameters, MAP_TYPE));
    }

    Optional<Map<String, Object>> toCustomParameters(String model, Map<String, Object> customParameters) {
        if (customParameters == null || customParameters.get(OUTPUT_CONFIG) == null) {
            return Optional.empty();
        }
        if (!(customParameters.get(OUTPUT_CONFIG) instanceof Map<?, ?> outputConfig)) {
            throw new BadRequestException(
                    "custom_parameters.output_config must be an object, model '%s'".formatted(model));
        }
        validateEffort(model, outputConfig.get(EFFORT));
        return outputConfig.isEmpty() ? Optional.empty() : Optional.of(Map.of(OUTPUT_CONFIG, outputConfig));
    }

    void validateOutputConfigEffort(String model, Map<String, Object> customParameters) {
        toCustomParameters(model, customParameters);
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
        var supported = Optional.ofNullable(model).map(LEVELS_BY_MODEL::get).orElse(ALL_LEVELS);
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

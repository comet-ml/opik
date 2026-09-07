package com.comet.opik.infrastructure.llm.gemini;

import com.comet.opik.infrastructure.llm.GeminiThinkingParams;
import dev.langchain4j.model.googleai.GeminiThinkingConfig;

import java.util.Map;
import java.util.Optional;

import static com.comet.opik.infrastructure.llm.GeminiThinkingParams.Level;

class GeminiThinkingConfigMapper {

    private GeminiThinkingConfigMapper() {
    }

    /**
     * Builds the Google AI Studio thinking config for a model.
     * <p>
     * {@code thinking_level} is Gemini 3+ only and earlier models reject it, so on 2.5 a level is translated into
     * its budget — the same translation Vertex needs at every version. {@code off} is always a zero budget.
     * <p>
     * Level and the legacy {@code thinking_budget} are mutually exclusive (sending both is a 400), so exactly one
     * is ever set.
     */
    static Optional<GeminiThinkingConfig> toThinkingConfig(String model, GeminiThinkingParams params) {
        if (params.isAbsent()) {
            return Optional.empty();
        }

        // Gemini 3+ cannot disable thinking, so "off" there is meaningless: send nothing rather than a
        // zero budget the model will not honour. Only the level is dropped — an explicit budget still
        // wins, as on 2.5. Unreachable from the UI, but the judge path takes custom_parameters as-is.
        if (params.level() == Level.OFF
                && params.budgetTokens() == null
                && GeminiThinkingParams.modelAcceptsLevel(model)) {
            return Optional.empty();
        }

        var builder = GeminiThinkingConfig.builder();
        boolean levelOnTheWire = params.level() != null
                && params.level() != Level.OFF
                && GeminiThinkingParams.modelAcceptsLevel(model);

        if (levelOnTheWire) {
            builder.thinkingLevel(params.level().wireValue());
        } else {
            // budgetForLevel() resolves an explicit budget first, then the level's budget, so `off` lands on 0 and a
            // 2.5 level lands on its mapped budget.
            Optional.ofNullable(params.budgetForLevel()).ifPresent(builder::thinkingBudget);
        }

        // include_thoughts is deliberately not forwarded: returnThinking is pinned FALSE on the model,
        // and at FALSE langchain4j discards thought parts — we would be billed for nothing. Wire it up
        // only alongside a way to surface the thoughts.

        return Optional.of(builder.build());
    }

    /**
     * Playground entry point, used from the MapStruct mapper where custom parameters are a plain map.
     */
    static GeminiThinkingConfig fromCustomParameters(String model, Map<String, Object> customParameters) {
        return toThinkingConfig(model, GeminiThinkingParams.from(customParameters)).orElse(null);
    }
}

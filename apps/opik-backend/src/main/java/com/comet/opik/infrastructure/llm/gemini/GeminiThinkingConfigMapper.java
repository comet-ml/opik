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
     * {@code thinking_level} is Gemini 3+ only — "If you use the thinking_level parameter with a model earlier than
     * Gemini 3, the model returns an error" — so on 2.5 a level is translated into the budget it maps to, the same
     * translation Vertex needs at every version. A level of {@code off} is always a zero budget: there is no "off"
     * level to send, and zero is how Gemini 2.5 Flash Lite already represents thinking being disabled.
     * <p>
     * {@code thinking_level} and the legacy {@code thinking_budget} are mutually exclusive — sending both returns a
     * 400 — so exactly one is ever set.
     */
    static Optional<GeminiThinkingConfig> toThinkingConfig(String model, GeminiThinkingParams params) {
        if (params.isAbsent()) {
            return Optional.empty();
        }

        // Gemini 3+ cannot disable thinking and does not accept a budget, so an "off" level there is
        // better ignored than translated into a zero budget the API would reject. The UI never offers
        // "off" for those models, but the judge path takes custom_parameters verbatim from the API.
        if (params.level() == Level.OFF && GeminiThinkingParams.modelAcceptsLevel(model)) {
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

        Optional.ofNullable(params.includeThoughts()).ifPresent(builder::includeThoughts);

        return Optional.of(builder.build());
    }

    /**
     * Playground entry point, used from the MapStruct mapper where custom parameters are a plain map.
     */
    static GeminiThinkingConfig fromCustomParameters(String model, Map<String, Object> customParameters) {
        return toThinkingConfig(model, GeminiThinkingParams.from(customParameters)).orElse(null);
    }
}

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
     * Builds the Google AI Studio thinking config, which takes the level directly rather than the budget Vertex needs.
     * <p>
     * A level of {@code off} is expressed as a zero budget instead: the API has no "off" level, and zero is how Gemini
     * 2.5 Flash Lite already represents thinking being disabled. That budget comes from
     * {@link GeminiThinkingParams#budgetForLevel()} so an explicit {@code budget_tokens} wins over the level here
     * exactly as it does on Vertex, rather than the two providers resolving the same parameters differently.
     */
    static Optional<GeminiThinkingConfig> toThinkingConfig(GeminiThinkingParams params) {
        if (params.isAbsent()) {
            return Optional.empty();
        }

        var builder = GeminiThinkingConfig.builder();

        if (params.level() == Level.OFF) {
            builder.thinkingBudget(params.budgetForLevel());
        } else {
            Optional.ofNullable(params.level()).map(Level::wireValue).ifPresent(builder::thinkingLevel);
            Optional.ofNullable(params.budgetTokens()).ifPresent(builder::thinkingBudget);
        }

        Optional.ofNullable(params.includeThoughts()).ifPresent(builder::includeThoughts);

        return Optional.of(builder.build());
    }

    /**
     * Playground entry point, used from the MapStruct mapper where custom parameters are a plain map.
     */
    static GeminiThinkingConfig fromCustomParameters(Map<String, Object> customParameters) {
        return toThinkingConfig(GeminiThinkingParams.from(customParameters)).orElse(null);
    }
}

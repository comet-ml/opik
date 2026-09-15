package com.comet.opik.infrastructure.llm.gemini;

import com.comet.opik.infrastructure.llm.GeminiThinkingParams;
import dev.langchain4j.model.googleai.GeminiThinkingConfig;

import java.util.Map;
import java.util.Optional;

class GeminiThinkingConfigMapper {

    private GeminiThinkingConfigMapper() {
    }

    /**
     * Builds the Google AI Studio thinking config for a model.
     * <p>
     * The level-versus-budget choice lives on {@link GeminiThinkingParams} because it is a property of the model rather
     * than of the provider, and Vertex makes the same choice. A config with neither field set would be an empty
     * thinking block, so that case yields no config at all.
     * <p>
     * {@code include_thoughts} is deliberately not forwarded. returnThinking is pinned to FALSE above this mapper
     * (Gemma 4 returns thought parts unconditionally and they would otherwise be concatenated into the answer), and
     * langchain4j's PartsAndContentsMapper drops thought parts outright at FALSE — so asking the API for them would
     * bill thinking tokens and return nothing. Wire it up only alongside a way to surface the thoughts.
     */
    static Optional<GeminiThinkingConfig> toThinkingConfig(String model, GeminiThinkingParams params) {
        if (params.isAbsent()) {
            return Optional.empty();
        }

        var level = params.wireLevelFor(model);
        var budget = params.wireBudgetFor(model);

        if (level.isEmpty() && budget.isEmpty()) {
            return Optional.empty();
        }

        var builder = GeminiThinkingConfig.builder();
        level.ifPresent(builder::thinkingLevel);
        budget.ifPresent(builder::thinkingBudget);

        return Optional.of(builder.build());
    }

    /**
     * Playground entry point, used from the MapStruct mapper where custom parameters are a plain map.
     */
    static GeminiThinkingConfig fromCustomParameters(String model, Map<String, Object> customParameters) {
        return toThinkingConfig(model, GeminiThinkingParams.from(customParameters)).orElse(null);
    }
}

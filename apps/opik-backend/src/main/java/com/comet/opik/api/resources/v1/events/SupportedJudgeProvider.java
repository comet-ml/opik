package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.LlmProvider;
import com.comet.opik.infrastructure.llm.antropic.AnthropicModelName;
import com.comet.opik.infrastructure.llm.gemini.GeminiModelName;
import com.comet.opik.infrastructure.llm.openai.OpenaiModelName;
import com.comet.opik.infrastructure.llm.vertexai.VertexAIModelName;

import java.util.Arrays;
import java.util.Optional;
import java.util.Set;

/**
 * Supported providers for test suite LLM-as-judge assertions, ordered by priority.
 * First connected provider wins.
 */
enum SupportedJudgeProvider {
    OPEN_AI(LlmProvider.OPEN_AI, OpenaiModelName.GPT_5_NANO.toString()),
    ANTHROPIC(LlmProvider.ANTHROPIC, AnthropicModelName.CLAUDE_HAIKU_4_5.toString()),
    GEMINI(LlmProvider.GEMINI, GeminiModelName.GEMINI_2_0_FLASH.toString()),
    VERTEX_AI(LlmProvider.VERTEX_AI, VertexAIModelName.GEMINI_2_5_FLASH.qualifiedName());

    private final LlmProvider provider;
    private final String model;

    SupportedJudgeProvider(LlmProvider provider, String model) {
        this.provider = provider;
        this.model = model;
    }

    /**
     * Resolves the LLM model for test suite assertions based on connected providers.
     * Returns the model for the highest-priority connected provider, or empty if none match.
     */
    static Optional<String> resolveModel(Set<LlmProvider> connectedProviders) {
        return Arrays.stream(values())
                .filter(judge -> connectedProviders.contains(judge.provider))
                .findFirst()
                .map(judge -> judge.model);
    }
}

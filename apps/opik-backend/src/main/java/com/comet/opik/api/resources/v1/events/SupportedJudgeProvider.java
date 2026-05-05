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
 *
 * <p><strong>Order rationale (empirical, OPIK-5745):</strong> the test-suite judge runs
 * with a prompt-driven agentic tool loop ({@code get_trace_spans}, {@code read}, {@code jq},
 * {@code search}) so it can verify assertions against truncated trace content. We exercised
 * this loop on a 244 KB trace with assertions that required reading the truncated input:
 * <ul>
 *   <li><strong>Anthropic Haiku 4.5</strong> — calls tools, drills into the trace via
 *       {@code search}/{@code jq}, returns correct scores. This is the only model in the
 *       supported list that reliably engages the tool loop in this configuration.</li>
 *   <li><strong>OpenAI {@code gpt-5-nano}, {@code gpt-4o-mini}, {@code gpt-5-mini},
 *       {@code gpt-5.1}</strong> — at most call {@code get_trace_spans} once and then
 *       answer from the visible context, even when the system prompt explicitly says
 *       "you MUST call get_trace_spans first … always verify by inspecting the trace".
 *       Result: confidently-wrong scores on assertions whose target content lives in the
 *       truncated portion of the trace input/output.</li>
 * </ul>
 *
 * <p>Anthropic is therefore listed first so any workspace with an Anthropic key configured
 * gets the tool-using judge by default. OpenAI remains as a fallback because (a) it works
 * for assertions that don't depend on truncated content and (b) workspaces without an
 * Anthropic key still need a judge. Gemini / Vertex have not been empirically validated for
 * the agentic-tool path; they're kept as further fallbacks behind the two tested providers.
 *
 * <p>If you change this order, re-run the test-suite assertion experiment from the design
 * doc (see FEATURE_DESIGN_LlmJudgeAgenticTools.md) and confirm the chosen judge still calls
 * {@code read} / {@code jq} / {@code search} when needed. A judge that skips tools silently
 * downgrades scoring quality without surfacing as an error.
 */
enum SupportedJudgeProvider {
    ANTHROPIC(LlmProvider.ANTHROPIC, AnthropicModelName.CLAUDE_HAIKU_4_5.toString()),
    OPEN_AI(LlmProvider.OPEN_AI, OpenaiModelName.GPT_5_1.toString()),
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

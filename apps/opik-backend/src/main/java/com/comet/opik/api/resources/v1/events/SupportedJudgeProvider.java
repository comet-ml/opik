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
 * <p><strong>Order rationale (preliminary, OPIK-5745):</strong> the test-suite judge runs
 * with a prompt-driven agentic tool loop ({@code get_trace_spans}, {@code read}, {@code jq},
 * {@code search}) so it can verify assertions against truncated trace content. During
 * OPIK-5745 we observed:
 * <ul>
 *   <li><strong>Anthropic Haiku 4.5</strong> reliably engages the tool loop —
 *       calls {@code get_trace_spans}, follows up with {@code read} / {@code search} /
 *       {@code jq} as needed, and reaches assertions about content that only exists in
 *       the truncated portion of the trace input.</li>
 *   <li><strong>OpenAI {@code gpt-5-nano}, {@code gpt-4o-mini}, {@code gpt-5-mini},
 *       {@code gpt-5.1}</strong> at most call {@code get_trace_spans} once and otherwise
 *       answer from the visible context, even with explicit "you MUST call tools first"
 *       guidance in the system prompt.</li>
 * </ul>
 *
 * <p>That tool-engagement asymmetry is what motivates listing Anthropic first: a judge
 * that calls tools can verify input-side assertions against truncated content; a judge
 * that doesn't cannot. The asymmetry was reproducible across multiple OpenAI tiers, so
 * this isn't a model-tier (cheap-vs-expensive) effect — it appears to be a
 * vendor/prompt-shape interaction.
 *
 * <p><strong>Caveat (please read before re-validating):</strong> the comparison runs that
 * led to this ordering were partially confounded by a separate agent-side bug in
 * {@code LlmProviderAnthropic.validateRequest} that caused some traces to be persisted
 * with empty {@code output} (see {@code BUG_LlmProviderAnthropic_maxCompletionTokens}
 * at the repo root). Output-side assertion judging in those runs was therefore not a
 * fair comparison — both Haiku and the OpenAI judges were observing an empty {@code "{}"}.
 * The conclusion that <em>tool engagement</em> differs between the providers is robust
 * (we confirmed it on assertions about the trace's input, which had real content); the
 * stronger conclusion that Haiku produces "correct scores on truncated content end-to-end"
 * still wants a clean re-test once the agent bug is fixed.
 *
 * <p>OpenAI remains as a fallback because (a) it works for assertions that don't depend
 * on truncated content and (b) workspaces without an Anthropic key still need a judge.
 * Gemini / Vertex have not been validated for the tool path; they sit behind the two
 * tested providers as further fallbacks.
 *
 * <p>If you change this order, re-run the test-suite assertion experiment from the design
 * doc (see FEATURE_DESIGN_LlmJudgeAgenticTools.md), ideally after the agent bug above is
 * fixed, and confirm the chosen judge still calls {@code read} / {@code jq} / {@code search}
 * when needed. A judge that skips tools silently downgrades scoring quality without
 * surfacing as an error.
 */
enum SupportedJudgeProvider {
    ANTHROPIC(LlmProvider.ANTHROPIC, AnthropicModelName.CLAUDE_HAIKU_4_5.toString()),
    OPEN_AI(LlmProvider.OPEN_AI, OpenaiModelName.GPT_4O_MINI.toString()),
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

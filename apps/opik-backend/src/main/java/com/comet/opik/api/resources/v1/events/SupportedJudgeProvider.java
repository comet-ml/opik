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
 * <p><strong>Order rationale (validated, OPIK-5745):</strong> the test-suite judge runs
 * with a prompt-driven agentic tool loop ({@code get_trace_spans}, {@code read}, {@code jq},
 * {@code search}) so it can verify assertions against truncated trace content. After
 * isolating an unrelated agent-side bug (see "History" below) we re-ran the comparison
 * with a real, non-empty trace ({@code gpt-5.1} as the agent under test, ~250 KB output)
 * and observed the following:
 * <ul>
 *   <li><strong>Anthropic Haiku 4.5</strong> with default {@code tool_choice=AUTO}:
 *       7 chat rounds, 5 tool calls ({@code read(FULL)} + 3× {@code search} + {@code jq}),
 *       ~32 s end-to-end. Reaches the input-side assertions about truncated content.</li>
 *   <li><strong>OpenAI judges with default {@code tool_choice=AUTO}</strong>
 *       ({@code gpt-5-nano}, {@code gpt-4o-mini}, {@code gpt-5-mini}, {@code gpt-5.1}):
 *       1 round, 0 tool calls, ~3–7 s. Answer from visible context regardless of "you MUST
 *       call tools" guidance in the system prompt. The behavior was identical across
 *       four OpenAI tiers — vendor/prompt-shape interaction, not a tier (cost) effect.</li>
 *   <li><strong>OpenAI judges with {@code tool_choice=REQUIRED} forced on the first
 *       call</strong> (the current scorer behaviour, see
 *       {@code OnlineScoringLlmAsJudgeScorer#evaluate}): {@code gpt-4o-mini} issues
 *       {@code get_trace_spans} + {@code read(FULL)} as parallel tool calls in round 1,
 *       then wraps up over 2 more rounds (AUTO) — 3 rounds, 2 tools, ~30 s. Comparable
 *       wall time to Haiku, fewer tool calls (no {@code search} / {@code jq}) since the
 *       full trace fits the model's working set after one {@code read(FULL)}.
 *       <ul>
 *         <li><strong>Avoid {@code gpt-5-nano} as the default</strong> even though it's
 *             cheaper. Under REQUIRED-on-first-call, nano satisfies the gate with
 *             {@code get_trace_spans} alone (~1.2 KB span-tree skeleton) and then judges
 *             from that summary without ever calling {@code read} for the actual span
 *             content. It produces a structurally-valid 4-score response, but for any
 *             assertion that depends on truncated content — which is the whole reason
 *             the tool loop exists — it's evaluating against the same skeleton the
 *             prompt already complains about. {@code gpt-4o-mini} is slightly more
 *             expensive but consistently pulls {@code read(FULL)} in round 1, so it
 *             actually loads the trace body before scoring.</li>
 *       </ul></li>
 * </ul>
 *
 * <p>Anthropic remains first because it engages the tool loop without needing the
 * REQUIRED-on-first-call lever, and it exercises {@code search} / {@code jq} which would
 * matter for traces too large to fit even at FULL tier. OpenAI is now a viable fallback
 * (rather than a strictly degraded one) because the REQUIRED forcing in the scorer flips
 * it from "skip tools entirely" to "call get_trace_spans + read at least once". Gemini and
 * Vertex have not been validated for the tool path; they sit behind the two tested
 * providers as further fallbacks.
 *
 * <p><strong>History (kept for context if anyone re-validates):</strong> the original
 * cross-tier OpenAI vs. Anthropic comparison was partially confounded by a separate
 * agent-side bug in {@code LlmProviderAnthropic.validateRequest} that caused traces with
 * an Anthropic agent to be persisted with empty {@code output} (see
 * {@code BUG_LlmProviderAnthropic_maxCompletionTokens} at the repo root). Once we switched
 * the agent under test to {@code gpt-5.1} (which doesn't hit that bug), traces had real
 * output and the comparison above was clean. The bug only affected Anthropic models in
 * the <em>agent</em> role; Anthropic in the <em>judge</em> role goes through a different
 * code path and was never affected.
 *
 * <p>If you change this order, re-run the test-suite assertion experiment from the design
 * doc (see FEATURE_DESIGN_LlmJudgeAgenticTools.md) and confirm the chosen judge still
 * calls {@code read} / {@code jq} / {@code search} when needed. A judge that skips tools
 * silently downgrades scoring quality without surfacing as an error.
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

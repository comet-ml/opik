package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.events.TraceToScoreLlmAsJudge;
import com.comet.opik.infrastructure.OnlineScoringConfig;
import lombok.experimental.UtilityClass;

import java.util.UUID;

/**
 * Single source of truth for whether the agentic tool path
 * (read / jq / search / get_trace_spans) is enabled for an LLM-as-judge
 * scoring run.
 *
 * <p>Two cases trigger tools:
 * <ul>
 *   <li>{@code experimentId != null} — test-suite assertion runs always
 *       use tools so the judge can drill into the trace's full execution.</li>
 *   <li>The estimated context size exceeds
 *       {@link OnlineScoringConfig#getAgenticToolsThresholdTokens()} AND
 *       {@link OnlineScoringConfig#isAgenticToolsEnabled()} is true — the
 *       online-scoring agentic path, gated on size so small traces stay on
 *       the cheaper inline path.</li>
 * </ul>
 *
 * <p>The same predicate gates two concerns that must stay in lockstep:
 * <ul>
 *   <li>Whether {@link TestSuitePromptConstants#systemPrompt(boolean)}
 *       includes the tool-teaching addendum.</li>
 *   <li>Whether {@code OnlineScoringLlmAsJudgeScorer} attaches tool
 *       specifications and runs the tool-call loop.</li>
 * </ul>
 *
 * <p>Keeping the gate in one place prevents the silent failure mode of
 * teaching the LLM about tools in the system prompt while the request
 * itself carries no tool specifications — the model would emit tool calls
 * the API has not declared.
 */
@UtilityClass
public final class LlmAsJudgeToolsMode {

    public static boolean shouldUseTools(UUID experimentId) {
        return experimentId != null;
    }

    public static boolean shouldUseTools(TraceToScoreLlmAsJudge message) {
        return shouldUseTools(message.experimentId());
    }

    /**
     * Size-aware variant. Returns true if either the experimentId path applies (test-suite
     * assertion) or the rendered context is estimated to exceed the configured threshold and
     * the agentic-tools path is enabled. Provider tool-calling support is checked separately
     * by the caller — a model whose provider doesn't support tools falls back to inline even
     * above the threshold.
     */
    public static boolean shouldUseTools(TraceToScoreLlmAsJudge message,
            int estimatedContextTokens, OnlineScoringConfig config) {
        if (shouldUseTools(message)) {
            return true;
        }
        return config.isAgenticToolsEnabled() && estimatedContextTokens >= config.getAgenticToolsThresholdTokens();
    }
}

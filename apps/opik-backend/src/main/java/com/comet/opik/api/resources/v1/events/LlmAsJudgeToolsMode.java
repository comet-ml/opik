package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.events.TraceToScoreLlmAsJudge;
import lombok.experimental.UtilityClass;

import java.util.UUID;

/**
 * Single source of truth for whether the agentic tool path
 * (read / jq / search / get_trace_spans) is enabled for an LLM-as-judge
 * scoring run.
 *
 * <p>Today the rule is "test-suite assertion runs only", expressed as
 * {@code experimentId != null} since assertion sampling is the only flow
 * that populates that field on the message. The same predicate gates two
 * concerns that must stay in lockstep:
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
}
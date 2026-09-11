package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.events.TraceToScoreLlmAsJudge;
import lombok.experimental.UtilityClass;

import java.util.UUID;

/**
 * Predicate for the experimentId-driven branch of the agentic-tools gate: test-suite
 * assertion runs ({@code experimentId != null}) always use tools so the judge can drill
 * into the trace's full execution. The size-based online-scoring branch is computed
 * locally in {@link OnlineScoringLlmAsJudgeScorer} because it also needs provider
 * support and config, which don't belong here.
 *
 * <p>The result must stay in lockstep with two things:
 * <ul>
 *   <li>Whether {@link TestSuitePromptConstants#systemPrompt(boolean)} includes the
 *       tool-teaching addendum.</li>
 *   <li>Whether the scorer attaches tool specifications and runs the tool-call loop.
 *       Teaching the LLM about tools while sending no tool specs makes it emit tool
 *       calls the API has not declared.</li>
 * </ul>
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

package com.comet.opik.domain.mapping.otel;

import com.comet.opik.domain.SpanType;
import com.comet.opik.domain.mapping.OpenTelemetryMappingRule;
import lombok.experimental.UtilityClass;

import java.util.List;
import java.util.Optional;

/**
 * Mapping rules for the spans that the Claude API exports (instrumentation scope
 * {@code com.anthropic.claude-platform}), for example the spans of Claude Managed Agents sessions.
 * <p>
 * These rules apply before the global rules, and only to spans of that scope. For these spans the
 * default bucket for unmapped attributes is metadata, so request details ({@code http.*},
 * {@code url.*}, {@code anthropic.request.id}, {@code anthropic.session.id}, ...) do not land in input.
 * The GenAI content attributes keep their global rules. The thread of the trace comes from the
 * session turn (see {@link ClaudePlatformTraces}), so the session thread id stays in metadata.
 */
@UtilityClass
public final class ClaudePlatformMappingRules {

    public static final String SOURCE = "ClaudePlatform";
    public static final String INSTRUMENTATION_SCOPE = "com.anthropic.claude-platform";

    private static final List<OpenTelemetryMappingRule> RULES = List.of(
            OpenTelemetryMappingRule.builder()
                    .rule("anthropic.tool_result.").isPrefix(true).source(SOURCE)
                    .outcome(OpenTelemetryMappingRule.Outcome.OUTPUT).spanType(SpanType.tool).build(),
            OpenTelemetryMappingRule.builder()
                    .rule("anthropic.session.outcome_evaluation.result").source(SOURCE)
                    .outcome(OpenTelemetryMappingRule.Outcome.OUTPUT).build(),
            OpenTelemetryMappingRule.builder()
                    .rule("anthropic.session.outcome_evaluation.explanation").source(SOURCE)
                    .outcome(OpenTelemetryMappingRule.Outcome.OUTPUT).build(),
            OpenTelemetryMappingRule.builder()
                    .rule("gen_ai.conversation.id").source(SOURCE)
                    .outcome(OpenTelemetryMappingRule.Outcome.METADATA).build());

    public static boolean isClaudePlatformScope(String scopeName) {
        return INSTRUMENTATION_SCOPE.equals(scopeName);
    }

    public static Optional<OpenTelemetryMappingRule> findRule(String key) {
        return RULES.stream().filter(rule -> rule.matches(key)).findFirst();
    }
}

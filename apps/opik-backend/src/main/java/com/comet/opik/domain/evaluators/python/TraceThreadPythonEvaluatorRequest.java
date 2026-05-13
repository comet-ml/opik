package com.comet.opik.domain.evaluators.python;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;

/**
 * Wire envelope for trace-thread Python evaluator runs.
 *
 * <p>{@code data} accepts two shapes:
 * <ul>
 *   <li><strong>List of {@link ChatMessage}</strong> — legacy single-positional signature
 *       {@code metric.score(messages)}. Untouched when a rule declares no extra arguments.</li>
 *   <li><strong>Map of kwargs</strong> — new opt-in shape used when the rule's
 *       {@code arguments} declares {@code spans} and/or {@code traces}. The sandbox runner
 *       unpacks the dict as keyword arguments to {@code metric.score(...)}, so the user's
 *       signature looks like {@code def score(self, messages, spans=None, traces=None)}.</li>
 * </ul>
 * Typed as {@link Object} (not a sealed union) because Jackson serializes both shapes
 * straight through and the Python side dispatches on {@code isinstance(data, dict)}.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@Builder(toBuilder = true)
public record TraceThreadPythonEvaluatorRequest(@NotEmpty String code, @NotNull Object data) {

    public static final String ROLE_ASSISTANT = "assistant";
    public static final String ROLE_USER = "user";

    /** Reserved kwargs keys recognized by the sandbox runner in the dict-shaped data form. */
    public static final String MESSAGES_KEY = "messages";
    public static final String SPANS_KEY = "spans";
    public static final String TRACES_KEY = "traces";

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @Builder(toBuilder = true)
    public record ChatMessage(@NotEmpty String role, @NotEmpty JsonNode content) {
    }

    @JsonProperty
    public String type() {
        return "trace_thread";
    }

}

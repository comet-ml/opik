package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;

@Getter
@RequiredArgsConstructor
public enum AlertEventType {
    TRACE_ERRORS("trace:errors"),
    TRACE_FEEDBACK_SCORE("trace:feedback_score"),
    TRACE_THREAD_FEEDBACK_SCORE("trace_thread:feedback_score"),
    PROMPT_CREATED("prompt:created"),
    PROMPT_COMMITTED("prompt:committed"),
    SPAN_GUARDRAILS_TRIGGERED("span:guardrails_triggered"),
    PROMPT_DELETED("prompt:deleted");

    @JsonValue
    private final String value;

    @JsonCreator
    public static AlertEventType fromString(String value) {
        return Arrays.stream(values())
                .filter(type -> type.value.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown Alert Event Type '%s'".formatted(value)));
    }
}

package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;
import java.util.Optional;

@Getter
@RequiredArgsConstructor
public enum AlertTriggerConfigType {
    SCOPE_PROJECT("scope:project"),
    THRESHOLD_FEEDBACK_SCORE("threshold:feedback_score"),
    THRESHOLD_COST("threshold:cost"),
    THRESHOLD_LATENCY("threshold:latency"),
    THRESHOLD_ERRORS("threshold:errors"),
    FILTER_GUARDRAIL_TYPE("filter:guardrail_type");

    @JsonValue
    private final String value;

    /**
     * The config type carrying the threshold condition for an event type, empty when the event is not
     * metrics-based. Single source for MetricsAlertJob's evaluation and AlertService's validation, so the two
     * cannot disagree about which configs need a threshold and a window.
     */
    public static Optional<AlertTriggerConfigType> thresholdTypeFor(AlertEventType eventType) {
        return switch (eventType) {
            case TRACE_COST -> Optional.of(THRESHOLD_COST);
            case TRACE_LATENCY -> Optional.of(THRESHOLD_LATENCY);
            case TRACE_ERRORS -> Optional.of(THRESHOLD_ERRORS);
            case TRACE_FEEDBACK_SCORE, TRACE_THREAD_FEEDBACK_SCORE -> Optional.of(THRESHOLD_FEEDBACK_SCORE);
            default -> Optional.empty();
        };
    }

    @JsonCreator
    public static AlertTriggerConfigType fromString(String value) {
        return Arrays.stream(values())
                .filter(type -> type.value.equals(value))
                .findFirst()
                .orElseThrow(
                        () -> new IllegalArgumentException("Unknown Alert Trigger Config Type '%s'".formatted(value)));
    }
}

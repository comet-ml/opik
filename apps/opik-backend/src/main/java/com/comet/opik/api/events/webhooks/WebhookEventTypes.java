package com.comet.opik.api.events.webhooks;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;

/**
 * Enum for webhook event types used throughout the application.
 * These event types help categorize different kinds of webhook notifications.
 */
@Getter
@RequiredArgsConstructor
public enum WebhookEventTypes {

    // Trace events
    TRACE_CREATED("trace.created"),
    TRACE_UPDATED("trace.updated"),
    TRACE_DELETED("trace.deleted"),

    // Span events
    SPAN_CREATED("span.created"),
    SPAN_UPDATED("span.updated"),
    SPAN_DELETED("span.deleted"),

    // Experiment events
    EXPERIMENT_CREATED("experiment.created"),
    EXPERIMENT_UPDATED("experiment.updated"),
    EXPERIMENT_DELETED("experiment.deleted"),

    // Dataset events
    DATASET_CREATED("dataset.created"),
    DATASET_UPDATED("dataset.updated"),
    DATASET_DELETED("dataset.deleted"),
    DATASET_ITEM_CREATED("dataset.item.created"),
    DATASET_ITEM_UPDATED("dataset.item.updated"),
    DATASET_ITEM_DELETED("dataset.item.deleted"),

    // Feedback events
    FEEDBACK_SCORE_CREATED("feedback_score.created"),
    FEEDBACK_SCORE_UPDATED("feedback_score.updated"),
    FEEDBACK_SCORE_DELETED("feedback_score.deleted"),

    // Project events
    PROJECT_CREATED("project.created"),
    PROJECT_UPDATED("project.updated"),
    PROJECT_DELETED("project.deleted"),

    // Evaluation events
    EVALUATION_STARTED("evaluation.started"),
    EVALUATION_COMPLETED("evaluation.completed"),
    EVALUATION_FAILED("evaluation.failed");

    @JsonValue
    private final String value;

    @JsonCreator
    public static WebhookEventTypes fromString(String value) {
        return Arrays.stream(values())
                .filter(type -> type.value.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown webhook event type: '%s'".formatted(value)));
    }
}

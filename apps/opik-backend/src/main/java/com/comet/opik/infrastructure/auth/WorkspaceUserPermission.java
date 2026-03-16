package com.comet.opik.infrastructure.auth;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum WorkspaceUserPermission {

    DASHBOARD_VIEW("dashboard_view"),
    EXPERIMENT_VIEW("experiment_view"),
    DATASET_VIEW("dataset_view"),
    ANNOTATION_QUEUE_VIEW("annotation_queue_view"),
    TRACE_SPAN_THREAD_LOG("trace_span_thread_log");

    @JsonValue
    private final String value;

}

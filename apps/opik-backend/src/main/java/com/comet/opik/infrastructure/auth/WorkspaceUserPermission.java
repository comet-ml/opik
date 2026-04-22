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
    TRACE_SPAN_THREAD_LOG("trace_span_thread_log"),
    PROJECT_DELETE("project_delete"),
    TRACE_DELETE("trace_delete"),
    DATASET_DELETE("dataset_delete"),
    ANNOTATION_QUEUE_DELETE("annotation_queue_delete"),
    PROMPT_DELETE("prompt_delete"),
    OPTIMIZATION_RUN_DELETE("optimization_run_delete"),
    WORKSPACE_SETTINGS_CONFIGURE("workspace_settings_configure"),
    AI_PROVIDER_UPDATE("ai_provider_update"),
    ANNOTATION_QUEUE_ANNOTATE("annotation_queue_annotate"),
    PROJECT_CREATE("project_create"),
    PROJECT_DATA_VIEW("project_data_view"),
    COMMENT_WRITE("comment_write"),
    TRACE_SPAN_THREAD_ANNOTATE("trace_span_thread_annotate"),
    TRACE_TAG("trace_tag"),
    ONLINE_EVALUATION_RULE_UPDATE("online_evaluation_rule_update"),
    ALERT_UPDATE("alert_update"),
    ANNOTATION_QUEUE_CREATE("annotation_queue_create");

    @JsonValue
    private final String value;

}

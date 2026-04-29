package com.comet.opik.infrastructure.auth;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum WorkspaceUserPermission {

    WORKSPACE_SETTINGS_CONFIGURE("workspace_settings_configure"),
    AI_PROVIDER_UPDATE("ai_provider_update"),

    PROJECT_CREATE("project_create"),
    PROJECT_DATA_VIEW("project_data_view"),
    PROJECT_DELETE("project_delete"),

    TRACE_SPAN_THREAD_LOG("trace_span_thread_log"),
    TRACE_SPAN_THREAD_ANNOTATE("trace_span_thread_annotate"),
    TRACE_DELETE("trace_delete"),

    ONLINE_EVALUATION_RULE_UPDATE("online_evaluation_rule_update"),
    ALERT_UPDATE("alert_update"),

    DASHBOARD_VIEW("dashboard_view"),
    DASHBOARD_CREATE("dashboard_create"),
    DASHBOARD_EDIT("dashboard_edit"),
    DASHBOARD_DELETE("dashboard_delete"),

    EXPERIMENT_VIEW("experiment_view"),
    EXPERIMENT_CREATE("experiment_create"),

    DATASET_VIEW("dataset_view"),
    DATASET_CREATE("dataset_create"),
    DATASET_EDIT("dataset_edit"),
    DATASET_DELETE("dataset_delete"),

    ANNOTATION_QUEUE_VIEW("annotation_queue_view"),
    ANNOTATION_QUEUE_CREATE("annotation_queue_create"),
    ANNOTATION_QUEUE_ANNOTATE("annotation_queue_annotate"),
    ANNOTATION_QUEUE_EDIT("annotation_queue_edit"),
    ANNOTATION_QUEUE_DELETE("annotation_queue_delete"),
    ANNOTATION_QUEUE_RESULTS_EXPORT("annotation_queue_results_export"),

    PROMPT_DELETE("prompt_delete"),

    OPTIMIZATION_RUN_VIEW("optimization_run_view"),
    OPTIMIZATION_RUN_DELETE("optimization_run_delete"),
    OPTIMIZATION_STUDIO_USE("optimization_studio_use");

    @JsonValue
    private final String value;

}

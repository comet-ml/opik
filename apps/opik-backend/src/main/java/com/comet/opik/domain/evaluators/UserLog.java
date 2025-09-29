package com.comet.opik.domain.evaluators;

public enum UserLog {
    AUTOMATION_RULE_EVALUATOR,
    WEBHOOK_EVENT_HANDLER,
    ;

    public static final String MARKER = "user_log";
    public static final String WORKSPACE_ID = "workspace_id";
    public static final String RULE_ID = "rule_id";
    public static final String WEBHOOK_ID = "webhook_id";
    public static final String TRACE_ID = "trace_id";
    public static final String THREAD_MODEL_ID = "thread_model_id";
    public static final String EVENT_ID = "event_id";
    public static final String ALERT_ID = "alert_id";
    public static final String RETRY_COUNT = "retry_count";

}

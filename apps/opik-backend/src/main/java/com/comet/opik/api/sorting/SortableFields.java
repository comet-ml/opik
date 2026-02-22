package com.comet.opik.api.sorting;

import lombok.experimental.UtilityClass;

@UtilityClass
public class SortableFields {

    public static final String ID = "id";
    public static final String NAME = "name";
    public static final String DESCRIPTION = "description";
    public static final String CREATED_AT = "created_at";
    public static final String LAST_UPDATED_AT = "last_updated_at";
    public static final String LAST_UPDATED_TRACE_AT = "last_updated_trace_at";
    public static final String LAST_CREATED_EXPERIMENT_AT = "last_created_experiment_at";
    public static final String LAST_CREATED_OPTIMIZATION_AT = "last_created_optimization_at";
    public static final String INPUT = "input";
    public static final String OUTPUT = "output";
    public static final String START_TIME = "start_time";
    public static final String END_TIME = "end_time";
    public static final String DURATION = "duration";
    public static final String DURATION_AGG = "duration.*";
    public static final String TTFT = "ttft";
    public static final String METADATA = "metadata";
    public static final String USAGE = "usage.*";
    public static final String TAGS = "tags";
    public static final String TRACE_ID = "trace_id";
    public static final String THREAD_ID = "thread_id";
    public static final String PARENT_SPAN_ID = "parent_span_id";
    public static final String TYPE = "type";
    public static final String MODEL = "model";
    public static final String PROVIDER = "provider";
    public static final String TOTAL_ESTIMATED_COST = "total_estimated_cost";
    public static final String TOTAL_ESTIMATED_COST_AVG = "total_estimated_cost_avg";
    public static final String ERROR_INFO = "error_info";
    public static final String CREATED_BY = "created_by";
    public static final String LAST_UPDATED_BY = "last_updated_by";
    public static final String SPAN_COUNT = "span_count";
    public static final String LLM_SPAN_COUNT = "llm_span_count";
    public static final String TRACE_COUNT = "trace_count";
    public static final String FEEDBACK_SCORES = "feedback_scores.*";
    public static final String EXPERIMENT_METRICS = "experiment_scores.*";
    public static final String NUMBER_OF_MESSAGES = "number_of_messages";
    public static final String STATUS = "status";
    public static final String VERSION_COUNT = "version_count";
    public static final String PROJECT_ID = "project_id";
    public static final String PROJECT_NAME = "project_name";
    public static final String INSTRUCTIONS = "instructions";
    public static final String WEBHOOK_URL = "webhook_url";
    public static final String DATA = "data.*"; // Truly dynamic - uses Map with parameter binding
    public static final String OUTPUT_WILDCARD = "output.*"; // JSON fields - use JSONExtractRaw, not parameter binding
    public static final String INPUT_WILDCARD = "input.*"; // JSON fields - use JSONExtractRaw, not parameter binding
    public static final String METADATA_WILDCARD = "metadata.*"; // JSON fields - use JSONExtractRaw, not parameter binding (metadata field already exists above)
    public static final String COMMENTS = "comments";
    public static final String EXPERIMENT_ID = "experiment_id";
    public static final String ENABLED = "enabled";
    public static final String SAMPLING_RATE = "sampling_rate";
    public static final String COMMIT = "commit";
    public static final String TEMPLATE = "template";
    public static final String CHANGE_DESCRIPTION = "change_description";
}

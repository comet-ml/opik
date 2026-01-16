package com.comet.opik.domain.filter;

import com.comet.opik.api.filter.AlertField;
import com.comet.opik.api.filter.AnnotationQueueField;
import com.comet.opik.api.filter.AutomationRuleEvaluatorField;
import com.comet.opik.api.filter.DatasetField;
import com.comet.opik.api.filter.DatasetItemField;
import com.comet.opik.api.filter.ExperimentField;
import com.comet.opik.api.filter.ExperimentsComparisonValidKnownField;
import com.comet.opik.api.filter.Field;
import com.comet.opik.api.filter.FieldType;
import com.comet.opik.api.filter.Filter;
import com.comet.opik.api.filter.Operator;
import com.comet.opik.api.filter.OptimizationField;
import com.comet.opik.api.filter.PromptField;
import com.comet.opik.api.filter.PromptVersionField;
import com.comet.opik.api.filter.SpanField;
import com.comet.opik.api.filter.TraceField;
import com.comet.opik.api.filter.TraceThreadField;
import com.comet.opik.api.sorting.SortingField;
import com.google.common.collect.ImmutableMap;
import io.r2dbc.spi.Statement;
import lombok.NonNull;
import org.apache.commons.lang3.StringUtils;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;
import java.util.stream.Collectors;

import static com.comet.opik.api.filter.Operator.NO_VALUE_OPERATORS;
import static com.comet.opik.api.sorting.SortingFactoryPromptVersions.PROMPT_VERSIONS_FIELDS_PATTERN;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.mapping;
import static java.util.stream.Collectors.toList;

public class FilterQueryBuilder {

    private static final String ANALYTICS_DB_AND_OPERATOR = "AND";

    public static final String JSONPATH_ROOT = "$";

    private static final String JSON_EXTRACT_RAW_TEMPLATE = "JSONExtractRaw(%s, '%s')";
    public static final String OUTPUT_FIELD_PREFIX = "output.";
    public static final String INPUT_FIELD_PREFIX = "input.";
    public static final String METADATA_FIELD_PREFIX = "metadata.";

    private static final String ID_DB = "id";
    private static final String NAME_DB = "name";
    private static final String DESCRIPTION_DB = "description";
    private static final String START_TIME_ANALYTICS_DB = "start_time";
    private static final String END_TIME_ANALYTICS_DB = "end_time";
    private static final String INPUT_ANALYTICS_DB = "input";
    private static final String OUTPUT_ANALYTICS_DB = "output";
    private static final String METADATA_ANALYTICS_DB = "metadata";
    private static final String MODEL_ANALYTICS_DB = "model";
    private static final String PROVIDER_ANALYTICS_DB = "provider";
    private static final String TOTAL_ESTIMATED_COST_ANALYTICS_DB = "total_estimated_cost";
    private static final String LLM_SPAN_COUNT_ANALYTICS_DB = "llm_span_count";
    private static final String TYPE_ANALYTICS_DB = "type";
    private static final String TAGS_DB = "tags";
    private static final String VERSION_COUNT_DB = "version_count";
    private static final String TEMPLATE_STRUCTURE_DB = "template_structure";
    private static final String USAGE_COMPLETION_TOKENS_ANALYTICS_DB = "usage['completion_tokens']";
    private static final String USAGE_PROMPT_TOKENS_ANALYTICS_DB = "usage['prompt_tokens']";
    private static final String USAGE_TOTAL_TOKENS_ANALYTICS_DB = "usage['total_tokens']";
    private static final String VALUE_ANALYTICS_DB = "value";
    private static final String DURATION_ANALYTICS_DB = "if(end_time IS NOT NULL AND start_time IS NOT NULL AND notEquals(start_time, toDateTime64('1970-01-01 00:00:00.000', 9)), (dateDiff('microsecond', start_time, end_time) / 1000.0), 0)";
    private static final String THREAD_ID_ANALYTICS_DB = "thread_id";
    private static final String DATASET_ID_ANALYTICS_DB = "dataset_id";
    private static final String PROMPT_IDS_ANALYTICS_DB = "prompt_ids";
    private static final String FIRST_MESSAGE_ANALYTICS_DB = "first_message";
    private static final String LAST_MESSAGE_ANALYTICS_DB = "last_message";
    private static final String CREATED_AT_DB = "created_at";
    private static final String LAST_UPDATED_AT_DB = "last_updated_at";
    private static final String CREATED_BY_DB = "created_by";
    private static final String LAST_UPDATED_BY_DB = "last_updated_by";
    private static final String LAST_CREATED_EXPERIMENT_AT_DB = "last_created_experiment_at";
    private static final String LAST_CREATED_OPTIMIZATION_AT_DB = "last_created_optimization_at";
    private static final String PROJECT_ID_DB = "project_id";
    private static final String INSTRUCTIONS_DB = "instructions";
    private static final String NUMBER_OF_MESSAGES_ANALYTICS_DB = "number_of_messages";
    private static final String FEEDBACK_SCORE_COUNT_DB = "fsc.feedback_scores_count";
    private static final String SPAN_FEEDBACK_SCORE_COUNT_DB = "sfsc.span_feedback_scores_count";
    private static final String EXPERIMENT_SCORE_COUNT_DB = "esc.experiment_scores_count";
    private static final String GUARDRAILS_RESULT_DB = "gagg.guardrails_result";
    private static final String VISIBILITY_MODE_DB = "visibility_mode";
    private static final String ERROR_INFO_DB = "error_info";
    private static final String STATUS_DB = "status";
    public static final String FEEDBACK_DEFINITIONS_DB = "feedback_definitions";
    public static final String SCOPE_DB = "scope";
    private static final String DATA_ANALYTICS_DB = "data";
    private static final String FULL_DATA_ANALYTICS_DB = "toString(data)";
    private static final String SOURCE_DB = "source";
    private static final String TRACE_ID_DB = "trace_id";
    private static final String SPAN_ID_DB = "span_id";
    public static final String ANNOTATION_QUEUE_IDS_ANALYTICS_DB = "taqi.annotation_queue_ids";
    public static final String THREAD_ANNOTATION_QUEUE_IDS_ANALYTICS_DB = "ttaqi.annotation_queue_ids";
    private static final String EXPERIMENT_ID_DB = "experiment_id";
    private static final String WEBHOOK_URL_DB = "webhook_url";
    private static final String ALERT_TYPE_DB = "alert_type";
    private static final String ENABLED_DB = "enabled";
    private static final String SAMPLING_RATE_DB = "sampling_rate";
    private static final String TYPE_DB = "type";
    private static final String COMMIT_DB = "commit";
    private static final String TEMPLATE_DB = "template";
    private static final String CHANGE_DESCRIPTION_DB = "change_description";

    /**
     * Set of all feedback score fields across different entity types (Trace, Span, TraceThread, Experiment, etc.).
     * Used to identify feedback score filters that require special handling in query building.
     */
    private static final Set<Field> FEEDBACK_SCORE_FIELDS = Set.of(
            TraceField.FEEDBACK_SCORES,
            TraceField.SPAN_FEEDBACK_SCORES,
            SpanField.FEEDBACK_SCORES,
            TraceThreadField.FEEDBACK_SCORES,
            ExperimentsComparisonValidKnownField.FEEDBACK_SCORES,
            ExperimentField.FEEDBACK_SCORES,
            ExperimentField.EXPERIMENT_SCORES);

    // Table alias prefixes for AutomationRuleEvaluator queries
    private static final String AUTOMATION_RULE_TABLE_ALIAS = "rule.%s";
    private static final String AUTOMATION_EVALUATOR_TABLE_ALIAS = "evaluator.%s";

    private static final Map<Operator, Map<FieldType, String>> ANALYTICS_DB_OPERATOR_MAP = new EnumMap<>(
            ImmutableMap.<Operator, Map<FieldType, String>>builder()
                    .put(Operator.CONTAINS, new EnumMap<>(Map.of(
                            FieldType.STRING, "ilike(%1$s, CONCAT('%%', :filter%2$d ,'%%'))",
                            FieldType.STRING_STATE_DB, "%1$s LIKE CONCAT('%%', :filter%2$d ,'%%')",
                            FieldType.LIST,
                            "arrayExists(element -> (ilike(element, CONCAT('%%', :filter%2$d ,'%%'))), %1$s) = 1",
                            FieldType.DICTIONARY,
                            "ilike(JSON_VALUE(%1$s, :filterKey%2$d), CONCAT('%%', :filter%2$d ,'%%'))",
                            // MAP values are stored as JSON strings (e.g., "hello" with quotes), so we use the raw value
                            // CONTAINS works because the pattern is found inside the value regardless of surrounding quotes
                            FieldType.DICTIONARY_STATE_DB,
                            "JSON_VALUE(%1$s, :filterKey%2$d) LIKE CONCAT('%%', :filter%2$d ,'%%')",
                            FieldType.MAP,
                            "ilike(arrayElement(mapValues(%1$s),indexOf(mapKeys(%1$s), :filterKey%2$d)), CONCAT('%%', :filter%2$d ,'%%'))")))
                    .put(Operator.NOT_CONTAINS, new EnumMap<>(Map.of(
                            FieldType.STRING, "notILike(%1$s, CONCAT('%%', :filter%2$d ,'%%'))",
                            FieldType.STRING_STATE_DB, "%1$s NOT LIKE CONCAT('%%', :filter%2$d ,'%%')",
                            FieldType.LIST,
                            "arrayExists(element -> (ilike(element, CONCAT('%%', :filter%2$d ,'%%'))), %1$s) = 0",
                            // MAP values are stored as JSON strings, NOT_CONTAINS works with raw value
                            FieldType.MAP,
                            "notILike(arrayElement(mapValues(%1$s),indexOf(mapKeys(%1$s), :filterKey%2$d)), CONCAT('%%', :filter%2$d ,'%%'))",
                            FieldType.DICTIONARY,
                            "notILike(JSON_VALUE(%1$s, :filterKey%2$d), CONCAT('%%', :filter%2$d ,'%%'))",
                            FieldType.DICTIONARY_STATE_DB,
                            "JSON_VALUE(%1$s, :filterKey%2$d) NOT LIKE CONCAT('%%', :filter%2$d ,'%%')")))
                    .put(Operator.STARTS_WITH, new EnumMap<>(Map.of(
                            FieldType.STRING, "startsWith(lower(%1$s), lower(:filter%2$d))",
                            FieldType.STRING_STATE_DB, "%1$s LIKE CONCAT(:filter%2$d ,'%%')",
                            // MAP values are stored as JSON strings with possible escaped quotes (e.g., "\"hello\"")
                            // First remove escaped quotes with replaceAll, then trim remaining quotes with trimBoth
                            FieldType.MAP,
                            "startsWith(lower(trimBoth(replaceAll(arrayElement(mapValues(%1$s),indexOf(mapKeys(%1$s), :filterKey%2$d)), '\\\\\"', ''), '\"')), lower(:filter%2$d))",
                            FieldType.DICTIONARY,
                            "startsWith(lower(JSON_VALUE(%1$s, :filterKey%2$d)), lower(:filter%2$d))",
                            FieldType.DICTIONARY_STATE_DB,
                            "JSON_VALUE(%1$s, :filterKey%2$d) LIKE CONCAT(:filter%2$d ,'%%')")))
                    .put(Operator.ENDS_WITH, new EnumMap<>(Map.of(
                            FieldType.STRING, "endsWith(lower(%1$s), lower(:filter%2$d))",
                            FieldType.STRING_STATE_DB, "%1$s LIKE CONCAT('%%', :filter%2$d)",
                            // MAP values are stored as JSON strings with possible escaped quotes (e.g., "\"hello\"")
                            // First remove escaped quotes with replaceAll, then trim remaining quotes with trimBoth
                            FieldType.MAP,
                            "endsWith(lower(trimBoth(replaceAll(arrayElement(mapValues(%1$s),indexOf(mapKeys(%1$s), :filterKey%2$d)), '\\\\\"', ''), '\"')), lower(:filter%2$d))",
                            FieldType.DICTIONARY,
                            "endsWith(lower(JSON_VALUE(%1$s, :filterKey%2$d)), lower(:filter%2$d))",
                            FieldType.DICTIONARY_STATE_DB,
                            "JSON_VALUE(%1$s, :filterKey%2$d) LIKE CONCAT('%%', :filter%2$d)")))
                    .put(Operator.EQUAL, new EnumMap<>(Map.ofEntries(
                            Map.entry(FieldType.STRING, "lower(%1$s) = lower(:filter%2$d)"),
                            Map.entry(FieldType.STRING_STATE_DB, "lower(%1$s) = lower(:filter%2$d)"),
                            Map.entry(FieldType.DATE_TIME, "%1$s = parseDateTime64BestEffort(:filter%2$d, 9)"),
                            Map.entry(FieldType.DATE_TIME_STATE_DB, "%1$s = :filter%2$d"),
                            Map.entry(FieldType.NUMBER, "%1$s = :filter%2$d"),
                            Map.entry(FieldType.DURATION, "%1$s = :filter%2$d"),
                            Map.entry(FieldType.LIST, "has(%1$s, :filter%2$d)"),
                            Map.entry(FieldType.FEEDBACK_SCORES_NUMBER,
                                    "has(groupArray(tuple(lower(name), %1$s)), tuple(lower(:filterKey%2$d), toDecimal64(:filter%2$d, 9))) = 1"),
                            Map.entry(FieldType.DICTIONARY,
                                    "lower(JSON_VALUE(%1$s, :filterKey%2$d)) = lower(:filter%2$d)"),
                            Map.entry(FieldType.DICTIONARY_STATE_DB,
                                    "lower(JSON_VALUE(%1$s, :filterKey%2$d)) = lower(:filter%2$d)"),
                            // MAP values are stored as JSON strings with possible escaped quotes (e.g., "\"hello\"")
                            // First remove escaped quotes with replaceAll, then trim remaining quotes with trimBoth
                            Map.entry(FieldType.MAP,
                                    "lower(trimBoth(replaceAll(arrayElement(mapValues(%1$s),indexOf(mapKeys(%1$s), :filterKey%2$d)), '\\\\\"', ''), '\"')) = lower(:filter%2$d)"),
                            Map.entry(FieldType.ENUM, "%1$s = :filter%2$d"))))
                    .put(Operator.NOT_EQUAL, new EnumMap<>(Map.ofEntries(
                            Map.entry(FieldType.STRING, "lower(%1$s) != lower(:filter%2$d)"),
                            Map.entry(FieldType.STRING_STATE_DB, "lower(%1$s) != lower(:filter%2$d)"),
                            Map.entry(FieldType.DATE_TIME, "%1$s != parseDateTime64BestEffort(:filter%2$d, 9)"),
                            Map.entry(FieldType.DATE_TIME_STATE_DB, "%1$s != :filter%2$d"),
                            Map.entry(FieldType.NUMBER, "%1$s != :filter%2$d"),
                            Map.entry(FieldType.DURATION, "%1$s != :filter%2$d"),
                            Map.entry(FieldType.LIST, "NOT has(%1$s, :filter%2$d)"),
                            Map.entry(FieldType.FEEDBACK_SCORES_NUMBER,
                                    "has(groupArray(tuple(lower(name), %1$s)), tuple(lower(:filterKey%2$d), toDecimal64(:filter%2$d, 9))) = 0"),
                            Map.entry(FieldType.DICTIONARY,
                                    "lower(JSON_VALUE(%1$s, :filterKey%2$d)) != lower(:filter%2$d)"),
                            Map.entry(FieldType.DICTIONARY_STATE_DB,
                                    "lower(JSON_VALUE(%1$s, :filterKey%2$d)) != lower(:filter%2$d)"),
                            // MAP values are stored as JSON strings with possible escaped quotes (e.g., "\"hello\"")
                            // First remove escaped quotes with replaceAll, then trim remaining quotes with trimBoth
                            Map.entry(FieldType.MAP,
                                    "lower(trimBoth(replaceAll(arrayElement(mapValues(%1$s),indexOf(mapKeys(%1$s), :filterKey%2$d)), '\\\\\"', ''), '\"')) != lower(:filter%2$d)"),
                            Map.entry(FieldType.ENUM, "%1$s != :filter%2$d"))))
                    .put(Operator.GREATER_THAN, new EnumMap<>(Map.ofEntries(
                            Map.entry(FieldType.STRING, "lower(%1$s) > lower(:filter%2$d)"),
                            Map.entry(FieldType.DATE_TIME, "%1$s > parseDateTime64BestEffort(:filter%2$d, 9)"),
                            Map.entry(FieldType.DATE_TIME_STATE_DB, "%1$s > :filter%2$d"),
                            Map.entry(FieldType.NUMBER, "%1$s > :filter%2$d"),
                            Map.entry(FieldType.DURATION, "%1$s > :filter%2$d"),
                            Map.entry(FieldType.FEEDBACK_SCORES_NUMBER,
                                    "arrayExists(element -> (element.1 = lower(:filterKey%2$d) AND element.2 > toDecimal64(:filter%2$d, 9)), groupArray(tuple(lower(name), %1$s))) = 1"),
                            Map.entry(FieldType.DICTIONARY,
                                    "toFloat64OrNull(JSON_VALUE(%1$s, :filterKey%2$d)) > toFloat64OrNull(:filter%2$d)"),
                            Map.entry(FieldType.DICTIONARY_STATE_DB,
                                    "JSON_VALUE(%1$s, :filterKey%2$d RETURNING DOUBLE NULL ON EMPTY NULL ON ERROR) > CAST(:filter%2$d AS DOUBLE)"))))
                    .put(Operator.GREATER_THAN_EQUAL, new EnumMap<>(Map.ofEntries(
                            Map.entry(FieldType.DATE_TIME, "%1$s >= parseDateTime64BestEffort(:filter%2$d, 9)"),
                            Map.entry(FieldType.DATE_TIME_STATE_DB, "%1$s >= :filter%2$d"),
                            Map.entry(FieldType.NUMBER, "%1$s >= :filter%2$d"),
                            Map.entry(FieldType.DURATION, "%1$s >= :filter%2$d"),
                            Map.entry(FieldType.FEEDBACK_SCORES_NUMBER,
                                    "arrayExists(element -> (element.1 = lower(:filterKey%2$d) AND element.2 >= toDecimal64(:filter%2$d, 9)), groupArray(tuple(lower(name), %1$s))) = 1"),
                            Map.entry(FieldType.DICTIONARY_STATE_DB,
                                    "JSON_VALUE(%1$s, :filterKey%2$d RETURNING DOUBLE NULL ON EMPTY NULL ON ERROR) >= CAST(:filter%2$d AS DOUBLE)"))))
                    .put(Operator.LESS_THAN, new EnumMap<>(Map.ofEntries(
                            Map.entry(FieldType.STRING, "lower(%1$s) < lower(:filter%2$d)"),
                            Map.entry(FieldType.DATE_TIME, "%1$s < parseDateTime64BestEffort(:filter%2$d, 9)"),
                            Map.entry(FieldType.DATE_TIME_STATE_DB, "%1$s < :filter%2$d"),
                            Map.entry(FieldType.NUMBER, "%1$s < :filter%2$d"),
                            Map.entry(FieldType.DURATION, "%1$s < :filter%2$d"),
                            Map.entry(FieldType.FEEDBACK_SCORES_NUMBER,
                                    "arrayExists(element -> (element.1 = lower(:filterKey%2$d) AND element.2 < toDecimal64(:filter%2$d, 9)), groupArray(tuple(lower(name), %1$s))) = 1"),
                            Map.entry(FieldType.DICTIONARY,
                                    "toFloat64OrNull(JSON_VALUE(%1$s, :filterKey%2$d)) < toFloat64OrNull(:filter%2$d)"),
                            Map.entry(FieldType.DICTIONARY_STATE_DB,
                                    "JSON_VALUE(%1$s, :filterKey%2$d RETURNING DOUBLE NULL ON EMPTY NULL ON ERROR) < CAST(:filter%2$d AS DOUBLE)"))))
                    .put(Operator.LESS_THAN_EQUAL, new EnumMap<>(Map.ofEntries(
                            Map.entry(FieldType.DATE_TIME, "%1$s <= parseDateTime64BestEffort(:filter%2$d, 9)"),
                            Map.entry(FieldType.DATE_TIME_STATE_DB, "%1$s <= :filter%2$d"),
                            Map.entry(FieldType.NUMBER, "%1$s <= :filter%2$d"),
                            Map.entry(FieldType.DURATION, "%1$s <= :filter%2$d"),
                            Map.entry(FieldType.FEEDBACK_SCORES_NUMBER,
                                    "arrayExists(element -> (element.1 = lower(:filterKey%2$d) AND element.2 <= toDecimal64(:filter%2$d, 9)), groupArray(tuple(lower(name), %1$s))) = 1"),
                            Map.entry(FieldType.DICTIONARY_STATE_DB,
                                    "JSON_VALUE(%1$s, :filterKey%2$d RETURNING DOUBLE NULL ON EMPTY NULL ON ERROR) <= CAST(:filter%2$d AS DOUBLE)"))))
                    .put(Operator.IS_EMPTY, new EnumMap<>(Map.of(
                            FieldType.FEEDBACK_SCORES_NUMBER,
                            "empty(arrayFilter(element -> (element = lower(:filterKey%2$d)), groupArray(lower(name)))) = 0",
                            FieldType.ERROR_CONTAINER,
                            "empty(%1$s)")))
                    .put(Operator.IS_NOT_EMPTY, new EnumMap<>(Map.of(
                            FieldType.FEEDBACK_SCORES_NUMBER,
                            "empty(arrayFilter(element -> (element = lower(:filterKey%2$d)), groupArray(lower(name)))) = 0",
                            FieldType.ERROR_CONTAINER,
                            "notEmpty(%1$s)")))
                    .build());

    private static final Map<TraceField, String> TRACE_FIELDS_MAP = new EnumMap<>(
            ImmutableMap.<TraceField, String>builder()
                    .put(TraceField.ID, ID_DB)
                    .put(TraceField.NAME, NAME_DB)
                    .put(TraceField.START_TIME, START_TIME_ANALYTICS_DB)
                    .put(TraceField.END_TIME, END_TIME_ANALYTICS_DB)
                    .put(TraceField.INPUT, INPUT_ANALYTICS_DB)
                    .put(TraceField.OUTPUT, OUTPUT_ANALYTICS_DB)
                    .put(TraceField.INPUT_JSON, INPUT_ANALYTICS_DB)
                    .put(TraceField.OUTPUT_JSON, OUTPUT_ANALYTICS_DB)
                    .put(TraceField.METADATA, METADATA_ANALYTICS_DB)
                    .put(TraceField.TOTAL_ESTIMATED_COST, TOTAL_ESTIMATED_COST_ANALYTICS_DB)
                    .put(TraceField.LLM_SPAN_COUNT, LLM_SPAN_COUNT_ANALYTICS_DB)
                    .put(TraceField.TAGS, TAGS_DB)
                    .put(TraceField.USAGE_COMPLETION_TOKENS, USAGE_COMPLETION_TOKENS_ANALYTICS_DB)
                    .put(TraceField.USAGE_PROMPT_TOKENS, USAGE_PROMPT_TOKENS_ANALYTICS_DB)
                    .put(TraceField.USAGE_TOTAL_TOKENS, USAGE_TOTAL_TOKENS_ANALYTICS_DB)
                    .put(TraceField.FEEDBACK_SCORES, VALUE_ANALYTICS_DB)
                    .put(TraceField.SPAN_FEEDBACK_SCORES, VALUE_ANALYTICS_DB)
                    .put(TraceField.DURATION, DURATION_ANALYTICS_DB)
                    .put(TraceField.THREAD_ID, THREAD_ID_ANALYTICS_DB)
                    .put(TraceField.GUARDRAILS, GUARDRAILS_RESULT_DB)
                    .put(TraceField.VISIBILITY_MODE, VISIBILITY_MODE_DB)
                    .put(TraceField.ERROR_INFO, ERROR_INFO_DB)
                    .put(TraceField.ANNOTATION_QUEUE_IDS, ANNOTATION_QUEUE_IDS_ANALYTICS_DB)
                    .put(TraceField.EXPERIMENT_ID, EXPERIMENT_ID_DB)
                    .put(TraceField.CREATED_AT, CREATED_AT_DB)
                    .put(TraceField.LAST_UPDATED_AT, LAST_UPDATED_AT_DB)
                    .build());

    private static final Map<TraceThreadField, String> TRACE_THREAD_FIELDS_MAP = new EnumMap<>(
            ImmutableMap.<TraceThreadField, String>builder()
                    .put(TraceThreadField.ID, ID_DB)
                    .put(TraceThreadField.NUMBER_OF_MESSAGES, NUMBER_OF_MESSAGES_ANALYTICS_DB)
                    .put(TraceThreadField.FIRST_MESSAGE, FIRST_MESSAGE_ANALYTICS_DB)
                    .put(TraceThreadField.LAST_MESSAGE, LAST_MESSAGE_ANALYTICS_DB)
                    .put(TraceThreadField.DURATION, DURATION_ANALYTICS_DB)
                    .put(TraceThreadField.CREATED_AT, CREATED_AT_DB)
                    .put(TraceThreadField.LAST_UPDATED_AT, LAST_UPDATED_AT_DB)
                    .put(TraceThreadField.START_TIME, START_TIME_ANALYTICS_DB)
                    .put(TraceThreadField.END_TIME, END_TIME_ANALYTICS_DB)
                    .put(TraceThreadField.FEEDBACK_SCORES, VALUE_ANALYTICS_DB)
                    .put(TraceThreadField.STATUS, STATUS_DB)
                    .put(TraceThreadField.TAGS, TAGS_DB)
                    .put(TraceThreadField.ANNOTATION_QUEUE_IDS, THREAD_ANNOTATION_QUEUE_IDS_ANALYTICS_DB)
                    .build());

    private static final Map<SpanField, String> SPAN_FIELDS_MAP = new EnumMap<>(
            ImmutableMap.<SpanField, String>builder()
                    .put(SpanField.ID, ID_DB)
                    .put(SpanField.NAME, NAME_DB)
                    .put(SpanField.START_TIME, START_TIME_ANALYTICS_DB)
                    .put(SpanField.END_TIME, END_TIME_ANALYTICS_DB)
                    .put(SpanField.INPUT, INPUT_ANALYTICS_DB)
                    .put(SpanField.OUTPUT, OUTPUT_ANALYTICS_DB)
                    .put(SpanField.INPUT_JSON, INPUT_ANALYTICS_DB)
                    .put(SpanField.OUTPUT_JSON, OUTPUT_ANALYTICS_DB)
                    .put(SpanField.METADATA, METADATA_ANALYTICS_DB)
                    .put(SpanField.MODEL, MODEL_ANALYTICS_DB)
                    .put(SpanField.PROVIDER, PROVIDER_ANALYTICS_DB)
                    .put(SpanField.TOTAL_ESTIMATED_COST, TOTAL_ESTIMATED_COST_ANALYTICS_DB)
                    .put(SpanField.TAGS, TAGS_DB)
                    .put(SpanField.USAGE_COMPLETION_TOKENS, USAGE_COMPLETION_TOKENS_ANALYTICS_DB)
                    .put(SpanField.USAGE_PROMPT_TOKENS, USAGE_PROMPT_TOKENS_ANALYTICS_DB)
                    .put(SpanField.USAGE_TOTAL_TOKENS, USAGE_TOTAL_TOKENS_ANALYTICS_DB)
                    .put(SpanField.FEEDBACK_SCORES, VALUE_ANALYTICS_DB)
                    .put(SpanField.DURATION, DURATION_ANALYTICS_DB)
                    .put(SpanField.ERROR_INFO, ERROR_INFO_DB)
                    .put(SpanField.TYPE, TYPE_ANALYTICS_DB)
                    .put(SpanField.TRACE_ID, TRACE_ID_DB)
                    .build());

    private static final Map<ExperimentField, String> EXPERIMENT_FIELDS_MAP = new EnumMap<>(
            ImmutableMap.<ExperimentField, String>builder()
                    .put(ExperimentField.METADATA, METADATA_ANALYTICS_DB)
                    .put(ExperimentField.DATASET_ID, DATASET_ID_ANALYTICS_DB)
                    .put(ExperimentField.PROMPT_IDS, PROMPT_IDS_ANALYTICS_DB)
                    .put(ExperimentField.TAGS, TAGS_DB)
                    .put(ExperimentField.FEEDBACK_SCORES, VALUE_ANALYTICS_DB)
                    .put(ExperimentField.EXPERIMENT_SCORES, VALUE_ANALYTICS_DB)
                    .build());

    private static final Map<OptimizationField, String> OPTIMIZATION_FIELDS_MAP = new EnumMap<>(
            ImmutableMap.<OptimizationField, String>builder()
                    .put(OptimizationField.METADATA, METADATA_ANALYTICS_DB)
                    .put(OptimizationField.DATASET_ID, DATASET_ID_ANALYTICS_DB)
                    .put(OptimizationField.STATUS, STATUS_DB)
                    .build());

    private static final Map<PromptField, String> PROMPT_FIELDS_MAP = new EnumMap<>(
            ImmutableMap.<PromptField, String>builder()
                    .put(PromptField.ID, ID_DB)
                    .put(PromptField.NAME, NAME_DB)
                    .put(PromptField.DESCRIPTION, DESCRIPTION_DB)
                    .put(PromptField.CREATED_AT, CREATED_AT_DB)
                    .put(PromptField.LAST_UPDATED_AT, LAST_UPDATED_AT_DB)
                    .put(PromptField.CREATED_BY, CREATED_BY_DB)
                    .put(PromptField.LAST_UPDATED_BY, LAST_UPDATED_BY_DB)
                    .put(PromptField.TAGS, TAGS_DB)
                    .put(PromptField.VERSION_COUNT, VERSION_COUNT_DB)
                    .put(PromptField.TEMPLATE_STRUCTURE, TEMPLATE_STRUCTURE_DB)
                    .build());

    private static final Map<PromptVersionField, String> PROMPT_VERSION_FIELDS_MAP = Map.ofEntries(
            Map.entry(PromptVersionField.ID, ID_DB),
            Map.entry(PromptVersionField.COMMIT, COMMIT_DB),
            Map.entry(PromptVersionField.TEMPLATE, TEMPLATE_DB),
            Map.entry(PromptVersionField.CHANGE_DESCRIPTION, CHANGE_DESCRIPTION_DB),
            Map.entry(PromptVersionField.METADATA, METADATA_ANALYTICS_DB),
            Map.entry(PromptVersionField.TYPE, TYPE_DB),
            Map.entry(PromptVersionField.TAGS, TAGS_DB),
            Map.entry(PromptVersionField.CREATED_AT, CREATED_AT_DB),
            Map.entry(PromptVersionField.CREATED_BY, CREATED_BY_DB)).entrySet().stream()
            .collect(Collectors.toUnmodifiableMap(
                    Map.Entry::getKey,
                    // Add the table alias as prefix to the db field name
                    entry -> PROMPT_VERSIONS_FIELDS_PATTERN.formatted(entry.getValue())));

    private static final Map<DatasetField, String> DATASET_FIELDS_MAP = new EnumMap<>(
            ImmutableMap.<DatasetField, String>builder()
                    .put(DatasetField.ID, ID_DB)
                    .put(DatasetField.NAME, NAME_DB)
                    .put(DatasetField.DESCRIPTION, DESCRIPTION_DB)
                    .put(DatasetField.TAGS, TAGS_DB)
                    .put(DatasetField.CREATED_AT, CREATED_AT_DB)
                    .put(DatasetField.CREATED_BY, CREATED_BY_DB)
                    .put(DatasetField.LAST_UPDATED_AT, LAST_UPDATED_AT_DB)
                    .put(DatasetField.LAST_UPDATED_BY, LAST_UPDATED_BY_DB)
                    .put(DatasetField.LAST_CREATED_EXPERIMENT_AT, LAST_CREATED_EXPERIMENT_AT_DB)
                    .put(DatasetField.LAST_CREATED_OPTIMIZATION_AT, LAST_CREATED_OPTIMIZATION_AT_DB)
                    .build());

    private static final Map<DatasetItemField, String> DATASET_ITEM_FIELDS_MAP = new EnumMap<>(
            ImmutableMap.<DatasetItemField, String>builder()
                    .put(DatasetItemField.ID, ID_DB)
                    .put(DatasetItemField.DATA, DATA_ANALYTICS_DB)
                    .put(DatasetItemField.FULL_DATA, FULL_DATA_ANALYTICS_DB)
                    .put(DatasetItemField.SOURCE, SOURCE_DB)
                    .put(DatasetItemField.TRACE_ID, TRACE_ID_DB)
                    .put(DatasetItemField.SPAN_ID, SPAN_ID_DB)
                    .put(DatasetItemField.TAGS, TAGS_DB)
                    .put(DatasetItemField.CREATED_AT, CREATED_AT_DB)
                    .put(DatasetItemField.LAST_UPDATED_AT, LAST_UPDATED_AT_DB)
                    .put(DatasetItemField.CREATED_BY, CREATED_BY_DB)
                    .put(DatasetItemField.LAST_UPDATED_BY, LAST_UPDATED_BY_DB)
                    .build());

    private static final Map<AnnotationQueueField, String> ANNOTATION_QUEUE_FIELDS_MAP = new EnumMap<>(
            ImmutableMap.<AnnotationQueueField, String>builder()
                    .put(AnnotationQueueField.ID, ID_DB)
                    .put(AnnotationQueueField.PROJECT_ID, PROJECT_ID_DB)
                    .put(AnnotationQueueField.NAME, NAME_DB)
                    .put(AnnotationQueueField.DESCRIPTION, DESCRIPTION_DB)
                    .put(AnnotationQueueField.INSTRUCTIONS, INSTRUCTIONS_DB)
                    .put(AnnotationQueueField.FEEDBACK_DEFINITION_NAMES, FEEDBACK_DEFINITIONS_DB)
                    .put(AnnotationQueueField.SCOPE, SCOPE_DB)
                    .put(AnnotationQueueField.CREATED_AT, CREATED_AT_DB)
                    .put(AnnotationQueueField.CREATED_BY, CREATED_BY_DB)
                    .put(AnnotationQueueField.LAST_UPDATED_AT, LAST_UPDATED_AT_DB)
                    .put(AnnotationQueueField.LAST_UPDATED_BY, LAST_UPDATED_BY_DB)
                    .build());

    private static final Map<AlertField, String> ALERT_FIELDS_MAP = new EnumMap<>(
            ImmutableMap.<AlertField, String>builder()
                    .put(AlertField.ID, ID_DB)
                    .put(AlertField.NAME, NAME_DB)
                    .put(AlertField.ALERT_TYPE, ALERT_TYPE_DB)
                    .put(AlertField.WEBHOOK_URL, WEBHOOK_URL_DB)
                    .put(AlertField.CREATED_AT, CREATED_AT_DB)
                    .put(AlertField.LAST_UPDATED_AT, LAST_UPDATED_AT_DB)
                    .put(AlertField.CREATED_BY, CREATED_BY_DB)
                    .put(AlertField.LAST_UPDATED_BY, LAST_UPDATED_BY_DB)
                    .build());

    private static final Map<AutomationRuleEvaluatorField, String> AUTOMATION_RULE_EVALUATOR_FIELDS_MAP = new EnumMap<>(
            ImmutableMap.<AutomationRuleEvaluatorField, String>builder()
                    .put(AutomationRuleEvaluatorField.ID, String.format(AUTOMATION_RULE_TABLE_ALIAS, ID_DB))
                    .put(AutomationRuleEvaluatorField.NAME, String.format(AUTOMATION_RULE_TABLE_ALIAS, NAME_DB))
                    .put(AutomationRuleEvaluatorField.TYPE, String.format(AUTOMATION_EVALUATOR_TABLE_ALIAS, TYPE_DB))
                    .put(AutomationRuleEvaluatorField.ENABLED, String.format(AUTOMATION_RULE_TABLE_ALIAS, ENABLED_DB))
                    .put(AutomationRuleEvaluatorField.SAMPLING_RATE,
                            String.format(AUTOMATION_RULE_TABLE_ALIAS, SAMPLING_RATE_DB))
                    .put(AutomationRuleEvaluatorField.CREATED_AT,
                            String.format(AUTOMATION_EVALUATOR_TABLE_ALIAS, CREATED_AT_DB))
                    .put(AutomationRuleEvaluatorField.LAST_UPDATED_AT,
                            String.format(AUTOMATION_EVALUATOR_TABLE_ALIAS, LAST_UPDATED_AT_DB))
                    .put(AutomationRuleEvaluatorField.CREATED_BY,
                            String.format(AUTOMATION_EVALUATOR_TABLE_ALIAS, CREATED_BY_DB))
                    .put(AutomationRuleEvaluatorField.LAST_UPDATED_BY,
                            String.format(AUTOMATION_EVALUATOR_TABLE_ALIAS, LAST_UPDATED_BY_DB))
                    .build());

    private static final Map<ExperimentsComparisonValidKnownField, String> EXPERIMENTS_COMPARISON_FIELDS_MAP = new EnumMap<>(
            ImmutableMap.<ExperimentsComparisonValidKnownField, String>builder()
                    .put(ExperimentsComparisonValidKnownField.ID, ID_DB)
                    .put(ExperimentsComparisonValidKnownField.SOURCE, SOURCE_DB)
                    .put(ExperimentsComparisonValidKnownField.TRACE_ID, TRACE_ID_DB)
                    .put(ExperimentsComparisonValidKnownField.SPAN_ID, SPAN_ID_DB)
                    .put(ExperimentsComparisonValidKnownField.CREATED_AT, CREATED_AT_DB)
                    .put(ExperimentsComparisonValidKnownField.LAST_UPDATED_AT, LAST_UPDATED_AT_DB)
                    .put(ExperimentsComparisonValidKnownField.CREATED_BY, CREATED_BY_DB)
                    .put(ExperimentsComparisonValidKnownField.LAST_UPDATED_BY, LAST_UPDATED_BY_DB)
                    .put(ExperimentsComparisonValidKnownField.DURATION, DURATION_ANALYTICS_DB)
                    .put(ExperimentsComparisonValidKnownField.FEEDBACK_SCORES, VALUE_ANALYTICS_DB)
                    .put(ExperimentsComparisonValidKnownField.OUTPUT, OUTPUT_ANALYTICS_DB)
                    .put(ExperimentsComparisonValidKnownField.TOTAL_ESTIMATED_COST, TOTAL_ESTIMATED_COST_ANALYTICS_DB)
                    .put(ExperimentsComparisonValidKnownField.USAGE_TOTAL_TOKENS, USAGE_TOTAL_TOKENS_ANALYTICS_DB)
                    .build());

    private static final Map<FilterStrategy, Set<? extends Field>> FILTER_STRATEGY_MAP = createFilterStrategyMap();

    private static Map<FilterStrategy, Set<? extends Field>> createFilterStrategyMap() {
        Map<FilterStrategy, Set<? extends Field>> map = new EnumMap<>(FilterStrategy.class);

        map.put(FilterStrategy.TRACE, Set.of(
                TraceField.ID,
                TraceField.NAME,
                TraceField.START_TIME,
                TraceField.END_TIME,
                TraceField.INPUT,
                TraceField.OUTPUT,
                TraceField.INPUT_JSON,
                TraceField.OUTPUT_JSON,
                TraceField.METADATA,
                TraceField.TAGS,
                TraceField.DURATION,
                TraceField.THREAD_ID,
                TraceField.GUARDRAILS,
                TraceField.VISIBILITY_MODE,
                TraceField.ERROR_INFO));

        map.put(FilterStrategy.EXPERIMENT_AGGREGATION, Set.of(
                TraceField.EXPERIMENT_ID));

        map.put(FilterStrategy.TRACE_AGGREGATION, Set.of(
                TraceField.USAGE_COMPLETION_TOKENS,
                TraceField.USAGE_PROMPT_TOKENS,
                TraceField.USAGE_TOTAL_TOKENS,
                TraceField.TOTAL_ESTIMATED_COST,
                TraceField.LLM_SPAN_COUNT));

        map.put(FilterStrategy.ANNOTATION_AGGREGATION, Set.of(
                TraceField.ANNOTATION_QUEUE_IDS,
                TraceThreadField.ANNOTATION_QUEUE_IDS));

        map.put(FilterStrategy.SPAN, Set.of(
                SpanField.ID,
                SpanField.NAME,
                SpanField.START_TIME,
                SpanField.END_TIME,
                SpanField.INPUT,
                SpanField.OUTPUT,
                SpanField.INPUT_JSON,
                SpanField.OUTPUT_JSON,
                SpanField.METADATA,
                SpanField.MODEL,
                SpanField.PROVIDER,
                SpanField.TOTAL_ESTIMATED_COST,
                SpanField.TAGS,
                SpanField.USAGE_COMPLETION_TOKENS,
                SpanField.USAGE_PROMPT_TOKENS,
                SpanField.USAGE_TOTAL_TOKENS,
                SpanField.DURATION,
                SpanField.ERROR_INFO,
                SpanField.TYPE,
                SpanField.TRACE_ID));

        map.put(FilterStrategy.FEEDBACK_SCORES, Set.of(
                TraceField.FEEDBACK_SCORES,
                SpanField.FEEDBACK_SCORES,
                ExperimentsComparisonValidKnownField.FEEDBACK_SCORES,
                TraceThreadField.FEEDBACK_SCORES,
                ExperimentField.FEEDBACK_SCORES));

        map.put(FilterStrategy.TRACE_SPAN_FEEDBACK_SCORES, Set.of(TraceField.SPAN_FEEDBACK_SCORES));

        map.put(FilterStrategy.SPAN_FEEDBACK_SCORES, Set.of(SpanField.FEEDBACK_SCORES));

        map.put(FilterStrategy.EXPERIMENT_SCORES, Set.of(ExperimentField.EXPERIMENT_SCORES));

        map.put(FilterStrategy.EXPERIMENT_ITEM, Set.of(
                ExperimentsComparisonValidKnownField.OUTPUT,
                ExperimentsComparisonValidKnownField.DURATION));

        map.put(FilterStrategy.EXPERIMENT, Set.of(
                ExperimentField.METADATA,
                ExperimentField.DATASET_ID,
                ExperimentField.PROMPT_IDS,
                ExperimentField.TAGS));

        map.put(FilterStrategy.PROMPT, Set.of(
                PromptField.ID,
                PromptField.NAME,
                PromptField.DESCRIPTION,
                PromptField.CREATED_AT,
                PromptField.LAST_UPDATED_AT,
                PromptField.CREATED_BY,
                PromptField.LAST_UPDATED_BY,
                PromptField.TAGS,
                PromptField.VERSION_COUNT,
                PromptField.TEMPLATE_STRUCTURE));

        map.put(FilterStrategy.PROMPT_VERSION, Set.of(
                PromptVersionField.ID,
                PromptVersionField.COMMIT,
                PromptVersionField.TEMPLATE,
                PromptVersionField.CHANGE_DESCRIPTION,
                PromptVersionField.TYPE,
                PromptVersionField.TAGS,
                PromptVersionField.CREATED_AT,
                PromptVersionField.CREATED_BY));

        map.put(FilterStrategy.DATASET, Set.of(
                DatasetField.ID,
                DatasetField.NAME,
                DatasetField.DESCRIPTION,
                DatasetField.TAGS,
                DatasetField.CREATED_AT,
                DatasetField.CREATED_BY,
                DatasetField.LAST_UPDATED_AT,
                DatasetField.LAST_UPDATED_BY,
                DatasetField.LAST_CREATED_EXPERIMENT_AT,
                DatasetField.LAST_CREATED_OPTIMIZATION_AT));

        map.put(FilterStrategy.ANNOTATION_QUEUE, Set.of(
                AnnotationQueueField.ID,
                AnnotationQueueField.PROJECT_ID,
                AnnotationQueueField.NAME,
                AnnotationQueueField.DESCRIPTION,
                AnnotationQueueField.INSTRUCTIONS,
                AnnotationQueueField.FEEDBACK_DEFINITION_NAMES,
                AnnotationQueueField.SCOPE,
                AnnotationQueueField.CREATED_AT,
                AnnotationQueueField.CREATED_BY,
                AnnotationQueueField.LAST_UPDATED_AT,
                AnnotationQueueField.LAST_UPDATED_BY));

        map.put(FilterStrategy.TRACE_THREAD, Set.of(
                TraceThreadField.ID,
                TraceThreadField.NUMBER_OF_MESSAGES,
                TraceThreadField.FIRST_MESSAGE,
                TraceThreadField.LAST_MESSAGE,
                TraceThreadField.DURATION,
                TraceThreadField.CREATED_AT,
                TraceThreadField.LAST_UPDATED_AT,
                TraceThreadField.START_TIME,
                TraceThreadField.END_TIME,
                TraceThreadField.STATUS,
                TraceThreadField.TAGS));

        map.put(FilterStrategy.DATASET_ITEM, Set.of(
                DatasetItemField.ID,
                DatasetItemField.DATA,
                DatasetItemField.FULL_DATA,
                DatasetItemField.SOURCE,
                DatasetItemField.TRACE_ID,
                DatasetItemField.SPAN_ID,
                DatasetItemField.TAGS,
                DatasetItemField.CREATED_AT,
                DatasetItemField.LAST_UPDATED_AT,
                DatasetItemField.CREATED_BY,
                DatasetItemField.LAST_UPDATED_BY,
                // Also include ExperimentsComparisonValidKnownField variants for experiment items
                ExperimentsComparisonValidKnownField.ID,
                ExperimentsComparisonValidKnownField.SOURCE,
                ExperimentsComparisonValidKnownField.TRACE_ID,
                ExperimentsComparisonValidKnownField.SPAN_ID,
                ExperimentsComparisonValidKnownField.CREATED_AT,
                ExperimentsComparisonValidKnownField.LAST_UPDATED_AT,
                ExperimentsComparisonValidKnownField.CREATED_BY,
                ExperimentsComparisonValidKnownField.LAST_UPDATED_BY));

        map.put(FilterStrategy.ALERT, Set.of(
                AlertField.ID,
                AlertField.NAME,
                AlertField.ALERT_TYPE,
                AlertField.WEBHOOK_URL,
                AlertField.CREATED_AT,
                AlertField.LAST_UPDATED_AT,
                AlertField.CREATED_BY,
                AlertField.LAST_UPDATED_BY));

        map.put(FilterStrategy.AUTOMATION_RULE_EVALUATOR, Set.of(
                AutomationRuleEvaluatorField.ID,
                AutomationRuleEvaluatorField.NAME,
                AutomationRuleEvaluatorField.TYPE,
                AutomationRuleEvaluatorField.ENABLED,
                AutomationRuleEvaluatorField.SAMPLING_RATE,
                AutomationRuleEvaluatorField.CREATED_AT,
                AutomationRuleEvaluatorField.LAST_UPDATED_AT,
                AutomationRuleEvaluatorField.CREATED_BY,
                AutomationRuleEvaluatorField.LAST_UPDATED_BY));

        map.put(FilterStrategy.OPTIMIZATION, Set.of(
                OptimizationField.METADATA,
                OptimizationField.DATASET_ID,
                OptimizationField.STATUS));

        return map;
    }

    private static final Set<FieldType> KEY_SUPPORTED_FIELDS_SET = EnumSet.of(
            FieldType.DICTIONARY,
            FieldType.DICTIONARY_STATE_DB,
            FieldType.MAP,
            FieldType.FEEDBACK_SCORES_NUMBER);

    public Map<Field, List<Operator>> getUnSupportedOperators(@NonNull Field... fields) {
        return Arrays.stream(fields)
                .flatMap(field -> ANALYTICS_DB_OPERATOR_MAP.entrySet().stream()
                        .filter(entry -> !entry.getValue().containsKey(field.getType()))
                        .map(entry -> Map.entry(field, entry.getKey())))
                .collect(groupingBy(Map.Entry::getKey, mapping(Map.Entry::getValue, toList())));
    }

    public Map<Field, List<Operator>> getSupportedOperators(@NonNull Field... fields) {
        return Arrays.stream(fields)
                .flatMap(field -> ANALYTICS_DB_OPERATOR_MAP.entrySet().stream()
                        .filter(entry -> entry.getValue().containsKey(field.getType()))
                        .map(entry -> Map.entry(field, entry.getKey())))
                .collect(groupingBy(Map.Entry::getKey, mapping(Map.Entry::getValue, toList())));
    }

    public static String toAnalyticsDbOperator(@NonNull Filter filter) {
        return ANALYTICS_DB_OPERATOR_MAP.get(filter.operator()).get(filter.field().getType());
    }

    public static Optional<Boolean> hasGuardrailsFilter(@NonNull List<? extends Filter> filters) {
        return filters.stream()
                .filter(filter -> filter.field() == TraceField.GUARDRAILS)
                .findFirst()
                .map(filter -> true);
    }

    public static Optional<String> toAnalyticsDbFilters(
            @NonNull List<? extends Filter> filters, @NonNull FilterStrategy filterStrategy) {
        var stringJoiner = new StringJoiner(" %s ".formatted(ANALYTICS_DB_AND_OPERATOR));
        stringJoiner.setEmptyValue("");
        for (var i = 0; i < filters.size(); i++) {
            var filter = filters.get(i);
            if (getFieldsByStrategy(filterStrategy, filter).orElse(Set.of()).contains(filter.field())
                    || filter.field().isDynamic(filterStrategy)) {
                stringJoiner.add(toAnalyticsDbFilter(filter, i, filterStrategy));
            }
        }
        var analyticsDbFilters = stringJoiner.toString();
        return StringUtils.isBlank(analyticsDbFilters)
                ? Optional.empty()
                : Optional.of("(%s)".formatted(analyticsDbFilters));
    }

    private static Optional<Set<? extends Field>> getFieldsByStrategy(FilterStrategy filterStrategy, Filter filter) {
        // we want to apply the is empty filter only in the case below
        if (filter.operator() == Operator.IS_EMPTY && filterStrategy == FilterStrategy.FEEDBACK_SCORES_IS_EMPTY) {
            return Optional.of(FILTER_STRATEGY_MAP.get(FilterStrategy.FEEDBACK_SCORES));
        }

        if (filter.operator() == Operator.IS_EMPTY
                && filterStrategy == FilterStrategy.TRACE_SPAN_FEEDBACK_SCORES_IS_EMPTY) {
            return Optional.of(FILTER_STRATEGY_MAP.get(FilterStrategy.TRACE_SPAN_FEEDBACK_SCORES));
        }

        if (filter.operator() == Operator.IS_EMPTY && filterStrategy == FilterStrategy.SPAN_FEEDBACK_SCORES_IS_EMPTY) {
            return Optional.of(FILTER_STRATEGY_MAP.get(FilterStrategy.SPAN_FEEDBACK_SCORES));
        }

        if (filter.operator() == Operator.IS_EMPTY && filterStrategy == FilterStrategy.EXPERIMENT_SCORES_IS_EMPTY) {
            return Optional.of(FILTER_STRATEGY_MAP.get(FilterStrategy.EXPERIMENT_SCORES));
        }

        if (isNotEmptyScoresFilter(filterStrategy, filter)) {
            return Optional.empty();
        }

        if (filter.operator() == Operator.IS_EMPTY && isFeedbackScore(filter)) {
            return Optional.empty();
        }

        return Optional.ofNullable(FILTER_STRATEGY_MAP.get(filterStrategy));
    }

    private static boolean isNotEmptyScoresFilter(FilterStrategy filterStrategy, Filter filter) {
        return filter.operator() == Operator.IS_NOT_EMPTY
                && Set.of(FilterStrategy.FEEDBACK_SCORES_IS_EMPTY, FilterStrategy.TRACE_SPAN_FEEDBACK_SCORES_IS_EMPTY,
                        FilterStrategy.SPAN_FEEDBACK_SCORES_IS_EMPTY, FilterStrategy.EXPERIMENT_SCORES_IS_EMPTY)
                        .contains(filterStrategy);
    }

    private static boolean isFeedbackScore(Filter filter) {
        return FEEDBACK_SCORE_FIELDS.contains(filter.field());
    }

    private static String toAnalyticsDbFilter(Filter filter, int i, FilterStrategy filterStrategy) {
        var template = toAnalyticsDbOperator(filter);
        var formattedTemplate = template.formatted(getAnalyticsDbField(filter.field(), filterStrategy, i), i);
        return "(%s)".formatted(formattedTemplate);
    }

    private static String getAnalyticsDbField(Field field, FilterStrategy filterStrategy, int i) {
        // this is a special case where the DB field is determined by the filter strategy rather than the filter field
        if (filterStrategy == FilterStrategy.FEEDBACK_SCORES_IS_EMPTY) {
            return FEEDBACK_SCORE_COUNT_DB;
        }

        if (filterStrategy == FilterStrategy.TRACE_SPAN_FEEDBACK_SCORES_IS_EMPTY) {
            return SPAN_FEEDBACK_SCORE_COUNT_DB;
        }

        if (filterStrategy == FilterStrategy.EXPERIMENT_SCORES_IS_EMPTY) {
            return EXPERIMENT_SCORE_COUNT_DB;
        }

        return switch (field) {
            case TraceField traceField -> TRACE_FIELDS_MAP.get(traceField);
            case SpanField spanField -> SPAN_FIELDS_MAP.get(spanField);
            case ExperimentField experimentField -> EXPERIMENT_FIELDS_MAP.get(experimentField);
            case ExperimentsComparisonValidKnownField experimentsComparisonValidKnownField ->
                EXPERIMENTS_COMPARISON_FIELDS_MAP.get(experimentsComparisonValidKnownField);
            case TraceThreadField traceThreadField -> TRACE_THREAD_FIELDS_MAP.get(traceThreadField);
            case PromptField promptField -> PROMPT_FIELDS_MAP.get(promptField);
            case PromptVersionField promptVersionField -> PROMPT_VERSION_FIELDS_MAP.get(promptVersionField);
            case DatasetField datasetField -> DATASET_FIELDS_MAP.get(datasetField);
            case DatasetItemField datasetItemField -> DATASET_ITEM_FIELDS_MAP.get(datasetItemField);
            case AnnotationQueueField annotationQueueField -> ANNOTATION_QUEUE_FIELDS_MAP.get(annotationQueueField);
            case AlertField alertField -> ALERT_FIELDS_MAP.get(alertField);
            case AutomationRuleEvaluatorField automationRuleEvaluatorField ->
                AUTOMATION_RULE_EVALUATOR_FIELDS_MAP.get(automationRuleEvaluatorField);
            case OptimizationField optimizationField -> OPTIMIZATION_FIELDS_MAP.get(optimizationField);
            default -> {

                if (field.isDynamic(filterStrategy)) {
                    yield filterStrategy.dbFormattedField(field).formatted(i);
                }

                throw new IllegalArgumentException(
                        "Unknown type for field '%s', type '%s'".formatted(field, field.getClass()));
            }
        };
    }

    public static Statement bind(
            @NonNull Statement statement,
            @NonNull List<? extends Filter> filters,
            @NonNull FilterStrategy filterStrategy) {
        for (var i = 0; i < filters.size(); i++) {
            var filter = filters.get(i);
            if (getFieldsByStrategy(filterStrategy, filter).orElse(Set.of()).contains(filter.field())
                    || filter.field().isDynamic(filterStrategy)) {

                if (filter.field().isDynamic(filterStrategy)) {
                    String fieldName = filter.field().getQueryParamField();

                    // For EXPERIMENT_ITEM, split fields like "output.some_field" into column name and JSON path
                    // Only bind the JSON path (column name is embedded in SQL template)
                    if (filterStrategy == FilterStrategy.EXPERIMENT_ITEM && fieldName.contains(".")) {
                        int firstDot = fieldName.indexOf('.');
                        String jsonKey = fieldName.substring(firstDot + 1);
                        String jsonPath = JSONPATH_ROOT + "." + jsonKey;

                        statement = statement.bind("dynamicJsonPath%d".formatted(i), jsonPath);
                    } else if (filterStrategy == FilterStrategy.DATASET_ITEM && fieldName.contains(".")) {
                        // For DATASET_ITEM, fields like "data.expected_answer" map to data['expected_answer']
                        // Extract the key name (the part after the first dot) and bind it
                        int firstDot = fieldName.indexOf('.');
                        String keyName = fieldName.substring(firstDot + 1);

                        statement = statement.bind("dynamicField%d".formatted(i), keyName);
                    } else if (filterStrategy == FilterStrategy.PROMPT_VERSION && fieldName.contains(".")) {
                        var jsonPath = getStateSQLJsonPath(fieldName);
                        statement = statement.bind("dynamicJsonPath%d".formatted(i), jsonPath);
                    } else {
                        // Default dynamic field binding for other strategies
                        statement = statement.bind("dynamicField%d".formatted(i), fieldName);
                    }
                }

                if (!NO_VALUE_OPERATORS.contains(filter.operator())) {
                    statement.bind("filter%d".formatted(i), filter.value());
                }

                if (StringUtils.isNotBlank(filter.key())
                        && KEY_SUPPORTED_FIELDS_SET.contains(filter.field().getType())) {
                    var key = getKey(filter);
                    statement = statement.bind("filterKey%d".formatted(i), key);
                }
            }
        }
        return statement;
    }

    public Map<String, Object> toStateSQLMapping(@NonNull List<? extends Filter> filters) {
        return toStateSQLMapping(filters, null);
    }

    public Map<String, Object> toStateSQLMapping(
            @NonNull List<? extends Filter> filters, FilterStrategy filterStrategy) {
        Map<String, Object> stateSQLMapping = new HashMap<>();
        for (var i = 0; i < filters.size(); i++) {
            var filter = filters.get(i);
            stateSQLMapping.put("filter%d".formatted(i), filter.value());

            // Handle dynamic fields
            if (filterStrategy != null && filter.field().isDynamic(filterStrategy)) {
                var fieldName = filter.field().getQueryParamField();
                if (filterStrategy == FilterStrategy.PROMPT_VERSION && fieldName.contains(".")) {
                    var jsonPath = getStateSQLJsonPath(fieldName);
                    stateSQLMapping.put("dynamicJsonPath%d".formatted(i), jsonPath);
                }
            }

            // Handle filter keys for DICTIONARY fields
            if (StringUtils.isNotBlank(filter.key())
                    && KEY_SUPPORTED_FIELDS_SET.contains(filter.field().getType())) {
                var key = getKey(filter);
                stateSQLMapping.put("filterKey%d".formatted(i), key);
            }
        }

        return stateSQLMapping;
    }

    /**
     * Generates a JSON path for dynamic fields, typically metadata, for the state DB (MySQL) SQL.
     * Splits fields, e.g: "metadata.environment" into JSON path format: $."environment"
     * Uses quoted dot notation to handle keys with spaces and special characters.
     *
     * @param fieldName Full field name like "metadata.environment"
     * @return JSON path in format $."key" e.g: $."environment"
     */
    private static String getStateSQLJsonPath(String fieldName) {
        var jsonKey = fieldName.substring(fieldName.indexOf('.') + 1);
        return getSQLJsonPath(jsonKey);
    }

    private static String getSQLJsonPath(String jsonKey) {
        return "%s.\"%s\"".formatted(JSONPATH_ROOT, jsonKey);
    }

    private static String getKey(Filter filter) {

        if (filter.key().startsWith(JSONPATH_ROOT)
                || (filter.field().getType() != FieldType.DICTIONARY
                        && filter.field().getType() != FieldType.DICTIONARY_STATE_DB)) {
            return filter.key();
        }

        if (filter.key().startsWith("[") || filter.key().startsWith(".")) {
            return "%s%s".formatted(JSONPATH_ROOT, filter.key());
        }

        if (filter.field().getType() == FieldType.DICTIONARY_STATE_DB) {
            return getSQLJsonPath(filter.key());
        }

        return "%s.%s".formatted(JSONPATH_ROOT, filter.key());
    }

    /**
     * Builds field mapping for DatasetItem JSON fields (output, input, metadata).
     * These fields are stored as JSON strings in ClickHouse, so we need to use JSONExtractRaw
     * instead of bracket notation. We use literal keys instead of bind parameters
     * to avoid the dynamic field tuple wrapping.
     * <p>
     * This is used for sorting DatasetItem fields.
     *
     * @param sortingFields the sorting fields from the request
     * @return a map from field name to ClickHouse SQL expression
     */
    public Map<String, String> buildDatasetItemFieldMapping(@NonNull List<SortingField> sortingFields) {
        Map<String, String> fieldMapping = new HashMap<>();

        for (SortingField field : sortingFields) {
            String fieldName = field.field();

            // Check if this is a JSON field (output, input, or metadata)
            // Use literal keys instead of bind parameters to avoid dynamic field handling
            if (fieldName.startsWith(OUTPUT_FIELD_PREFIX)) {
                String key = fieldName.substring(OUTPUT_FIELD_PREFIX.length());
                fieldMapping.put(fieldName,
                        JSON_EXTRACT_RAW_TEMPLATE.formatted("output", key));
            } else if (fieldName.startsWith(INPUT_FIELD_PREFIX)) {
                String key = fieldName.substring(INPUT_FIELD_PREFIX.length());
                fieldMapping.put(fieldName,
                        JSON_EXTRACT_RAW_TEMPLATE.formatted("input", key));
            } else if (fieldName.startsWith(METADATA_FIELD_PREFIX)) {
                String key = fieldName.substring(METADATA_FIELD_PREFIX.length());
                fieldMapping.put(fieldName,
                        JSON_EXTRACT_RAW_TEMPLATE.formatted("metadata", key));
            }
            // For other fields (including feedback_scores, data, etc.), use default dbField()
        }

        return fieldMapping;
    }

    /**
     * Builds a search filter SQL condition for DatasetItem search.
     * Uses multiSearchAnyCaseInsensitive to search within the data field.
     *
     * @param searchText the search text (non-blank)
     * @return SQL filter condition string
     */
    public String buildDatasetItemSearchFilter(@NonNull String searchText) {
        return "multiSearchAnyCaseInsensitive(toString(data), :searchTerms) > 0";
    }

    /**
     * Binds search terms to a statement.
     * Splits the search text by whitespace and binds as an array.
     *
     * @param statement the R2DBC statement
     * @param searchText the search text to split and bind
     * @return the statement with bound search terms
     */
    public Statement bindSearchTerms(@NonNull Statement statement, @NonNull String searchText) {
        String[] searchTerms = searchText.split("\\s+");
        return statement.bind("searchTerms", searchTerms);
    }
}

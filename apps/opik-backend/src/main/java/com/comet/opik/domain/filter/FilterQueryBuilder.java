package com.comet.opik.domain.filter;

import com.comet.opik.api.filter.AnnotationQueueField;
import com.comet.opik.api.filter.DatasetField;
import com.comet.opik.api.filter.ExperimentField;
import com.comet.opik.api.filter.ExperimentsComparisonValidKnownField;
import com.comet.opik.api.filter.Field;
import com.comet.opik.api.filter.FieldType;
import com.comet.opik.api.filter.Filter;
import com.comet.opik.api.filter.Operator;
import com.comet.opik.api.filter.PromptField;
import com.comet.opik.api.filter.SpanField;
import com.comet.opik.api.filter.TraceField;
import com.comet.opik.api.filter.TraceThreadField;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
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

import static com.comet.opik.api.filter.Operator.NO_VALUE_OPERATORS;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.mapping;
import static java.util.stream.Collectors.toList;

public class FilterQueryBuilder {

    private static final String ANALYTICS_DB_AND_OPERATOR = "AND";

    public static final String JSONPATH_ROOT = "$";

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
    private static final String USAGE_COMPLETION_TOKENS_ANALYTICS_DB = "usage['completion_tokens']";
    private static final String USAGE_PROMPT_TOKENS_ANALYTICS_DB = "usage['prompt_tokens']";
    private static final String USAGE_TOTAL_TOKENS_ANALYTICS_DB = "usage['total_tokens']";
    private static final String VALUE_ANALYTICS_DB = "value";
    private static final String DURATION_ANALYTICS_DB = "duration";
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
    private static final String GUARDRAILS_RESULT_DB = "gagg.guardrails_result";
    private static final String VISIBILITY_MODE_DB = "visibility_mode";
    private static final String ERROR_INFO_DB = "error_info";
    private static final String STATUS_DB = "status";
    public static final String FEEDBACK_DEFINITIONS_DB = "feedback_definitions";
    public static final String SCOPE_DB = "scope";

    private static final Map<Operator, Map<FieldType, String>> ANALYTICS_DB_OPERATOR_MAP = new EnumMap<>(
            ImmutableMap.<Operator, Map<FieldType, String>>builder()
                    .put(Operator.CONTAINS, new EnumMap<>(Map.of(
                            FieldType.STRING, "ilike(%1$s, CONCAT('%%', :filter%2$d ,'%%'))",
                            FieldType.STRING_STATE_DB, "%1$s LIKE CONCAT('%%', :filter%2$d ,'%%')",
                            FieldType.LIST,
                            "arrayExists(element -> (ilike(element, CONCAT('%%', :filter%2$d ,'%%'))), %1$s) = 1",
                            FieldType.DICTIONARY,
                            "ilike(JSON_VALUE(%1$s, :filterKey%2$d), CONCAT('%%', :filter%2$d ,'%%'))")))
                    .put(Operator.NOT_CONTAINS, new EnumMap<>(Map.of(
                            FieldType.STRING, "notILike(%1$s, CONCAT('%%', :filter%2$d ,'%%'))",
                            FieldType.STRING_STATE_DB, "%1$s NOT LIKE CONCAT('%%', :filter%2$d ,'%%')",
                            FieldType.DICTIONARY,
                            "notILike(JSON_VALUE(%1$s, :filterKey%2$d), CONCAT('%%', :filter%2$d ,'%%'))")))
                    .put(Operator.STARTS_WITH, new EnumMap<>(Map.of(
                            FieldType.STRING, "startsWith(lower(%1$s), lower(:filter%2$d))",
                            FieldType.STRING_STATE_DB, "%1$s LIKE CONCAT(:filter%2$d ,'%%')",
                            FieldType.DICTIONARY,
                            "startsWith(lower(JSON_VALUE(%1$s, :filterKey%2$d)), lower(:filter%2$d))")))
                    .put(Operator.ENDS_WITH, new EnumMap<>(Map.of(
                            FieldType.STRING, "endsWith(lower(%1$s), lower(:filter%2$d))",
                            FieldType.STRING_STATE_DB, "%1$s LIKE CONCAT('%%', :filter%2$d)",
                            FieldType.DICTIONARY,
                            "endsWith(lower(JSON_VALUE(%1$s, :filterKey%2$d)), lower(:filter%2$d))")))
                    .put(Operator.EQUAL, new EnumMap<>(Map.of(
                            FieldType.STRING, "lower(%1$s) = lower(:filter%2$d)",
                            FieldType.STRING_STATE_DB, "lower(%1$s) = lower(:filter%2$d)",
                            FieldType.DATE_TIME, "%1$s = parseDateTime64BestEffort(:filter%2$d, 9)",
                            FieldType.DATE_TIME_STATE_DB, "%1$s = :filter%2$d",
                            FieldType.NUMBER, "%1$s = :filter%2$d",
                            FieldType.FEEDBACK_SCORES_NUMBER,
                            "has(groupArray(tuple(lower(name), %1$s)), tuple(lower(:filterKey%2$d), toDecimal64(:filter%2$d, 9))) = 1",
                            FieldType.DICTIONARY,
                            "lower(JSON_VALUE(%1$s, :filterKey%2$d)) = lower(:filter%2$d)",
                            FieldType.ENUM, "%1$s = :filter%2$d")))
                    .put(Operator.NOT_EQUAL, new EnumMap<>(Map.of(
                            FieldType.STRING, "lower(%1$s) != lower(:filter%2$d)",
                            FieldType.STRING_STATE_DB, "lower(%1$s) != lower(:filter%2$d)",
                            FieldType.DATE_TIME, "%1$s != parseDateTime64BestEffort(:filter%2$d, 9)",
                            FieldType.DATE_TIME_STATE_DB, "%1$s != :filter%2$d",
                            FieldType.NUMBER, "%1$s != :filter%2$d",
                            FieldType.FEEDBACK_SCORES_NUMBER,
                            "has(groupArray(tuple(lower(name), %1$s)), tuple(lower(:filterKey%2$d), toDecimal64(:filter%2$d, 9))) = 0",
                            FieldType.DICTIONARY,
                            "lower(JSON_VALUE(%1$s, :filterKey%2$d)) != lower(:filter%2$d)",
                            FieldType.ENUM, "%1$s != :filter%2$d")))
                    .put(Operator.GREATER_THAN, new EnumMap<>(Map.of(
                            FieldType.STRING, "lower(%1$s) > lower(:filter%2$d)",
                            FieldType.DATE_TIME, "%1$s > parseDateTime64BestEffort(:filter%2$d, 9)",
                            FieldType.DATE_TIME_STATE_DB, "%1$s > :filter%2$d",
                            FieldType.NUMBER, "%1$s > :filter%2$d",
                            FieldType.FEEDBACK_SCORES_NUMBER,
                            "arrayExists(element -> (element.1 = lower(:filterKey%2$d) AND element.2 > toDecimal64(:filter%2$d, 9)), groupArray(tuple(lower(name), %1$s))) = 1",
                            FieldType.DICTIONARY,
                            "toFloat64OrNull(JSON_VALUE(%1$s, :filterKey%2$d)) > toFloat64OrNull(:filter%2$d)")))
                    .put(Operator.GREATER_THAN_EQUAL, new EnumMap<>(Map.of(
                            FieldType.DATE_TIME, "%1$s >= parseDateTime64BestEffort(:filter%2$d, 9)",
                            FieldType.DATE_TIME_STATE_DB, "%1$s >= :filter%2$d",
                            FieldType.NUMBER, "%1$s >= :filter%2$d",
                            FieldType.FEEDBACK_SCORES_NUMBER,
                            "arrayExists(element -> (element.1 = lower(:filterKey%2$d) AND element.2 >= toDecimal64(:filter%2$d, 9)), groupArray(tuple(lower(name), %1$s))) = 1")))
                    .put(Operator.LESS_THAN, new EnumMap<>(Map.of(
                            FieldType.STRING, "lower(%1$s) < lower(:filter%2$d)",
                            FieldType.DATE_TIME, "%1$s < parseDateTime64BestEffort(:filter%2$d, 9)",
                            FieldType.DATE_TIME_STATE_DB, "%1$s < :filter%2$d",
                            FieldType.NUMBER, "%1$s < :filter%2$d",
                            FieldType.FEEDBACK_SCORES_NUMBER,
                            "arrayExists(element -> (element.1 = lower(:filterKey%2$d) AND element.2 < toDecimal64(:filter%2$d, 9)), groupArray(tuple(lower(name), %1$s))) = 1",
                            FieldType.DICTIONARY,
                            "toFloat64OrNull(JSON_VALUE(%1$s, :filterKey%2$d)) < toFloat64OrNull(:filter%2$d)")))
                    .put(Operator.LESS_THAN_EQUAL, new EnumMap<>(Map.of(
                            FieldType.DATE_TIME, "%1$s <= parseDateTime64BestEffort(:filter%2$d, 9)",
                            FieldType.DATE_TIME_STATE_DB, "%1$s <= :filter%2$d",
                            FieldType.NUMBER, "%1$s <= :filter%2$d",
                            FieldType.FEEDBACK_SCORES_NUMBER,
                            "arrayExists(element -> (element.1 = lower(:filterKey%2$d) AND element.2 <= toDecimal64(:filter%2$d, 9)), groupArray(tuple(lower(name), %1$s))) = 1")))
                    .put(Operator.IS_EMPTY, new EnumMap<>(Map.of(
                            FieldType.FEEDBACK_SCORES_NUMBER,
                            "empty(arrayFilter(element -> (element = lower(:filterKey%2$d)), groupArray(lower(name)))) = 0",
                            FieldType.ERROR_CONTAINER,
                            "empty(%1$s)")))
                    .put(Operator.IS_NOT_EMPTY, new EnumMap<>(Map.of(
                            FieldType.FEEDBACK_SCORES_NUMBER,
                            "empty(arrayFilter(element -> (element.1 = lower(:filterKey%2$d)), groupArray(tuple(lower(name), %1$s)))) = 0",
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
                    .put(TraceField.DURATION, DURATION_ANALYTICS_DB)
                    .put(TraceField.THREAD_ID, THREAD_ID_ANALYTICS_DB)
                    .put(TraceField.GUARDRAILS, GUARDRAILS_RESULT_DB)
                    .put(TraceField.VISIBILITY_MODE, VISIBILITY_MODE_DB)
                    .put(TraceField.ERROR_INFO, ERROR_INFO_DB)
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
                    .build());

    private static final Map<ExperimentField, String> EXPERIMENT_FIELDS_MAP = new EnumMap<>(
            ImmutableMap.<ExperimentField, String>builder()
                    .put(ExperimentField.METADATA, METADATA_ANALYTICS_DB)
                    .put(ExperimentField.DATASET_ID, DATASET_ID_ANALYTICS_DB)
                    .put(ExperimentField.PROMPT_IDS, PROMPT_IDS_ANALYTICS_DB)
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
                    .build());

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

    private static final Map<ExperimentsComparisonValidKnownField, String> EXPERIMENTS_COMPARISON_FIELDS_MAP = new EnumMap<>(
            ImmutableMap.<ExperimentsComparisonValidKnownField, String>builder()
                    .put(ExperimentsComparisonValidKnownField.FEEDBACK_SCORES, VALUE_ANALYTICS_DB)
                    .put(ExperimentsComparisonValidKnownField.OUTPUT, OUTPUT_ANALYTICS_DB)
                    .build());

    private static final Map<FilterStrategy, Set<? extends Field>> FILTER_STRATEGY_MAP = new EnumMap<>(Map.of(
            FilterStrategy.TRACE, EnumSet.copyOf(ImmutableSet.<TraceField>builder()
                    .add(TraceField.ID)
                    .add(TraceField.NAME)
                    .add(TraceField.START_TIME)
                    .add(TraceField.END_TIME)
                    .add(TraceField.INPUT)
                    .add(TraceField.OUTPUT)
                    .add(TraceField.INPUT_JSON)
                    .add(TraceField.OUTPUT_JSON)
                    .add(TraceField.METADATA)
                    .add(TraceField.TAGS)
                    .add(TraceField.DURATION)
                    .add(TraceField.THREAD_ID)
                    .add(TraceField.GUARDRAILS)
                    .add(TraceField.VISIBILITY_MODE)
                    .add(TraceField.ERROR_INFO)
                    .build()),
            FilterStrategy.TRACE_AGGREGATION, EnumSet.copyOf(ImmutableSet.<TraceField>builder()
                    .add(TraceField.USAGE_COMPLETION_TOKENS)
                    .add(TraceField.USAGE_PROMPT_TOKENS)
                    .add(TraceField.USAGE_TOTAL_TOKENS)
                    .add(TraceField.TOTAL_ESTIMATED_COST)
                    .add(TraceField.LLM_SPAN_COUNT)
                    .build()),
            FilterStrategy.SPAN, EnumSet.copyOf(ImmutableSet.<SpanField>builder()
                    .add(SpanField.ID)
                    .add(SpanField.NAME)
                    .add(SpanField.START_TIME)
                    .add(SpanField.END_TIME)
                    .add(SpanField.INPUT)
                    .add(SpanField.OUTPUT)
                    .add(SpanField.INPUT_JSON)
                    .add(SpanField.OUTPUT_JSON)
                    .add(SpanField.METADATA)
                    .add(SpanField.MODEL)
                    .add(SpanField.PROVIDER)
                    .add(SpanField.TOTAL_ESTIMATED_COST)
                    .add(SpanField.TAGS)
                    .add(SpanField.USAGE_COMPLETION_TOKENS)
                    .add(SpanField.USAGE_PROMPT_TOKENS)
                    .add(SpanField.USAGE_TOTAL_TOKENS)
                    .add(SpanField.DURATION)
                    .add(SpanField.ERROR_INFO)
                    .add(SpanField.TYPE)
                    .build()),
            FilterStrategy.FEEDBACK_SCORES, ImmutableSet.<Field>builder()
                    .add(TraceField.FEEDBACK_SCORES)
                    .add(SpanField.FEEDBACK_SCORES)
                    .add(ExperimentsComparisonValidKnownField.FEEDBACK_SCORES)
                    .add(TraceThreadField.FEEDBACK_SCORES)
                    .build(),
            FilterStrategy.EXPERIMENT_ITEM, EnumSet.copyOf(ImmutableSet.<ExperimentsComparisonValidKnownField>builder()
                    .add(ExperimentsComparisonValidKnownField.OUTPUT)
                    .build()),
            FilterStrategy.EXPERIMENT, ImmutableSet.<Field>builder()
                    .add(ExperimentField.METADATA)
                    .add(ExperimentField.DATASET_ID)
                    .add(ExperimentField.PROMPT_IDS)
                    .build(),
            FilterStrategy.PROMPT, ImmutableSet.<Field>builder()
                    .add(PromptField.ID)
                    .add(PromptField.NAME)
                    .add(PromptField.DESCRIPTION)
                    .add(PromptField.CREATED_AT)
                    .add(PromptField.LAST_UPDATED_AT)
                    .add(PromptField.CREATED_BY)
                    .add(PromptField.LAST_UPDATED_BY)
                    .add(PromptField.TAGS)
                    .add(PromptField.VERSION_COUNT)
                    .build(),
            FilterStrategy.DATASET, EnumSet.copyOf(ImmutableSet.<DatasetField>builder()
                    .add(DatasetField.ID)
                    .add(DatasetField.NAME)
                    .add(DatasetField.DESCRIPTION)
                    .add(DatasetField.TAGS)
                    .add(DatasetField.CREATED_AT)
                    .add(DatasetField.CREATED_BY)
                    .add(DatasetField.LAST_UPDATED_AT)
                    .add(DatasetField.LAST_UPDATED_BY)
                    .add(DatasetField.LAST_CREATED_EXPERIMENT_AT)
                    .add(DatasetField.LAST_CREATED_OPTIMIZATION_AT)
                    .build()),
            FilterStrategy.ANNOTATION_QUEUE, EnumSet.copyOf(ImmutableSet.<AnnotationQueueField>builder()
                    .add(AnnotationQueueField.ID)
                    .add(AnnotationQueueField.PROJECT_ID)
                    .add(AnnotationQueueField.NAME)
                    .add(AnnotationQueueField.DESCRIPTION)
                    .add(AnnotationQueueField.INSTRUCTIONS)
                    .add(AnnotationQueueField.FEEDBACK_DEFINITION_NAMES)
                    .add(AnnotationQueueField.SCOPE)
                    .add(AnnotationQueueField.CREATED_AT)
                    .add(AnnotationQueueField.CREATED_BY)
                    .add(AnnotationQueueField.LAST_UPDATED_AT)
                    .add(AnnotationQueueField.LAST_UPDATED_BY)
                    .build()),
            FilterStrategy.TRACE_THREAD, EnumSet.copyOf(ImmutableSet.<TraceThreadField>builder()
                    .add(TraceThreadField.ID)
                    .add(TraceThreadField.NUMBER_OF_MESSAGES)
                    .add(TraceThreadField.FIRST_MESSAGE)
                    .add(TraceThreadField.LAST_MESSAGE)
                    .add(TraceThreadField.DURATION)
                    .add(TraceThreadField.CREATED_AT)
                    .add(TraceThreadField.LAST_UPDATED_AT)
                    .add(TraceThreadField.START_TIME)
                    .add(TraceThreadField.END_TIME)
                    .add(TraceThreadField.STATUS)
                    .add(TraceThreadField.TAGS)
                    .build())));

    private static final Set<FieldType> KEY_SUPPORTED_FIELDS_SET = EnumSet.of(
            FieldType.DICTIONARY,
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

    public String toAnalyticsDbOperator(@NonNull Filter filter) {
        return ANALYTICS_DB_OPERATOR_MAP.get(filter.operator()).get(filter.field().getType());
    }

    public Optional<Boolean> hasGuardrailsFilter(@NonNull List<? extends Filter> filters) {
        return filters.stream()
                .filter(filter -> filter.field() == TraceField.GUARDRAILS)
                .findFirst()
                .map(filter -> true);
    }

    public Optional<String> toAnalyticsDbFilters(
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

    private Optional<Set<? extends Field>> getFieldsByStrategy(FilterStrategy filterStrategy, Filter filter) {
        // we want to apply the is empty filter only in the case below
        if (filter.operator() == Operator.IS_EMPTY && filterStrategy == FilterStrategy.FEEDBACK_SCORES_IS_EMPTY) {
            return Optional.of(FILTER_STRATEGY_MAP.get(FilterStrategy.FEEDBACK_SCORES));
        } else if (filter.operator() == Operator.IS_EMPTY && isFeedBackScore(filter)) {
            return Optional.empty();
        }

        return Optional.ofNullable(FILTER_STRATEGY_MAP.get(filterStrategy));
    }

    private static boolean isFeedBackScore(Filter filter) {
        return Set.of(TraceField.FEEDBACK_SCORES, SpanField.FEEDBACK_SCORES, TraceThreadField.FEEDBACK_SCORES,
                ExperimentsComparisonValidKnownField.FEEDBACK_SCORES).contains(filter.field());
    }

    private String toAnalyticsDbFilter(Filter filter, int i, FilterStrategy filterStrategy) {
        var template = toAnalyticsDbOperator(filter);
        var formattedTemplate = template.formatted(getAnalyticsDbField(filter.field(), filterStrategy, i), i);
        return "(%s)".formatted(formattedTemplate);
    }

    private String getAnalyticsDbField(Field field, FilterStrategy filterStrategy, int i) {
        // this is a special case where the DB field is determined by the filter strategy rather than the filter field
        if (filterStrategy == FilterStrategy.FEEDBACK_SCORES_IS_EMPTY) {
            return FEEDBACK_SCORE_COUNT_DB;
        }

        return switch (field) {
            case TraceField traceField -> TRACE_FIELDS_MAP.get(traceField);
            case SpanField spanField -> SPAN_FIELDS_MAP.get(spanField);
            case ExperimentField experimentField -> EXPERIMENT_FIELDS_MAP.get(experimentField);
            case ExperimentsComparisonValidKnownField experimentsComparisonValidKnownField ->
                EXPERIMENTS_COMPARISON_FIELDS_MAP.get(experimentsComparisonValidKnownField);
            case TraceThreadField traceThreadField -> TRACE_THREAD_FIELDS_MAP.get(traceThreadField);
            case PromptField promptField -> PROMPT_FIELDS_MAP.get(promptField);
            case DatasetField datasetField -> DATASET_FIELDS_MAP.get(datasetField);
            case AnnotationQueueField annotationQueueField -> ANNOTATION_QUEUE_FIELDS_MAP.get(annotationQueueField);
            default -> {

                if (field.isDynamic(filterStrategy)) {
                    yield filterStrategy.dbFormattedField(field).formatted(i);
                }

                throw new IllegalArgumentException(
                        "Unknown type for field '%s', type '%s'".formatted(field, field.getClass()));
            }
        };
    }

    public Statement bind(
            @NonNull Statement statement,
            @NonNull List<? extends Filter> filters,
            @NonNull FilterStrategy filterStrategy) {
        for (var i = 0; i < filters.size(); i++) {
            var filter = filters.get(i);
            if (getFieldsByStrategy(filterStrategy, filter).orElse(Set.of()).contains(filter.field())
                    || filter.field().isDynamic(filterStrategy)) {

                if (filter.field().isDynamic(filterStrategy)) {
                    statement = statement.bind("dynamicField%d".formatted(i), filter.field().getQueryParamField());
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
        Map<String, Object> stateSQLMapping = new HashMap<>();
        for (var i = 0; i < filters.size(); i++) {
            var filter = filters.get(i);
            stateSQLMapping.put("filter%d".formatted(i), filter.value());
        }

        return stateSQLMapping;
    }

    private String getKey(Filter filter) {

        if (filter.key().startsWith(JSONPATH_ROOT) || filter.field().getType() != FieldType.DICTIONARY) {
            return filter.key();
        }

        if (filter.key().startsWith("[") || filter.key().startsWith(".")) {
            return "%s%s".formatted(JSONPATH_ROOT, filter.key());
        }

        return "%s.%s".formatted(JSONPATH_ROOT, filter.key());
    }
}

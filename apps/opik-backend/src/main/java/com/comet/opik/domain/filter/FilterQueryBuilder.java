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
import com.comet.opik.api.filter.PromptField;
import com.comet.opik.api.filter.SpanField;
import com.comet.opik.api.filter.TraceField;
import com.comet.opik.api.filter.TraceThreadField;
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
    private static final String DATA_ANALYTICS_DB = "toString(data)";
    private static final String SOURCE_DB = "source";
    private static final String TRACE_ID_DB = "trace_id";
    private static final String SPAN_ID_DB = "span_id";
    public static final String ANNOTATION_QUEUE_IDS_ANALYTICS_DB = "annotation_queue_ids";
    private static final String WEBHOOK_URL_DB = "webhook_url";
    private static final String ENABLED_DB = "enabled";
    private static final String SAMPLING_RATE_DB = "sampling_rate";
    private static final String TYPE_DB = "type";

    // Table alias prefixes for AutomationRuleEvaluator queries
    private static final String AUTOMATION_RULE_TABLE_ALIAS = "rule.%s";
    private static final String AUTOMATION_EVALUATOR_TABLE_ALIAS = "evaluator.%s";
    private static final String AUTOMATION_PROJECT_TABLE_ALIAS = "p.%s";

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
                    .put(TraceField.DURATION, DURATION_ANALYTICS_DB)
                    .put(TraceField.THREAD_ID, THREAD_ID_ANALYTICS_DB)
                    .put(TraceField.GUARDRAILS, GUARDRAILS_RESULT_DB)
                    .put(TraceField.VISIBILITY_MODE, VISIBILITY_MODE_DB)
                    .put(TraceField.ERROR_INFO, ERROR_INFO_DB)
                    .put(TraceField.ANNOTATION_QUEUE_IDS, ANNOTATION_QUEUE_IDS_ANALYTICS_DB)
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
                    .put(TraceThreadField.ANNOTATION_QUEUE_IDS, ANNOTATION_QUEUE_IDS_ANALYTICS_DB)
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

    private static final Map<DatasetItemField, String> DATASET_ITEM_FIELDS_MAP = new EnumMap<>(
            ImmutableMap.<DatasetItemField, String>builder()
                    .put(DatasetItemField.ID, ID_DB)
                    .put(DatasetItemField.DATA, DATA_ANALYTICS_DB)
                    .put(DatasetItemField.SOURCE, SOURCE_DB)
                    .put(DatasetItemField.TRACE_ID, TRACE_ID_DB)
                    .put(DatasetItemField.SPAN_ID, SPAN_ID_DB)
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
                    .put(AutomationRuleEvaluatorField.PROJECT_ID,
                            String.format(AUTOMATION_RULE_TABLE_ALIAS, PROJECT_ID_DB))
                    .put(AutomationRuleEvaluatorField.PROJECT_NAME,
                            String.format(AUTOMATION_PROJECT_TABLE_ALIAS, NAME_DB))
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
                    .put(ExperimentsComparisonValidKnownField.FEEDBACK_SCORES, VALUE_ANALYTICS_DB)
                    .put(ExperimentsComparisonValidKnownField.OUTPUT, OUTPUT_ANALYTICS_DB)
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
                SpanField.TYPE));

        map.put(FilterStrategy.FEEDBACK_SCORES, Set.of(
                TraceField.FEEDBACK_SCORES,
                SpanField.FEEDBACK_SCORES,
                ExperimentsComparisonValidKnownField.FEEDBACK_SCORES,
                TraceThreadField.FEEDBACK_SCORES));

        map.put(FilterStrategy.EXPERIMENT_ITEM, Set.of(
                ExperimentsComparisonValidKnownField.OUTPUT));

        map.put(FilterStrategy.EXPERIMENT, Set.of(
                ExperimentField.METADATA,
                ExperimentField.DATASET_ID,
                ExperimentField.PROMPT_IDS));

        map.put(FilterStrategy.PROMPT, Set.of(
                PromptField.ID,
                PromptField.NAME,
                PromptField.DESCRIPTION,
                PromptField.CREATED_AT,
                PromptField.LAST_UPDATED_AT,
                PromptField.CREATED_BY,
                PromptField.LAST_UPDATED_BY,
                PromptField.TAGS,
                PromptField.VERSION_COUNT));

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
                DatasetItemField.SOURCE,
                DatasetItemField.TRACE_ID,
                DatasetItemField.SPAN_ID,
                DatasetItemField.CREATED_AT,
                DatasetItemField.LAST_UPDATED_AT,
                DatasetItemField.CREATED_BY,
                DatasetItemField.LAST_UPDATED_BY));

        map.put(FilterStrategy.ALERT, Set.of(
                AlertField.ID,
                AlertField.NAME,
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
                AutomationRuleEvaluatorField.PROJECT_ID,
                AutomationRuleEvaluatorField.PROJECT_NAME,
                AutomationRuleEvaluatorField.CREATED_AT,
                AutomationRuleEvaluatorField.LAST_UPDATED_AT,
                AutomationRuleEvaluatorField.CREATED_BY,
                AutomationRuleEvaluatorField.LAST_UPDATED_BY));

        return map;
    }

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
        } else
            if (filter.operator() == Operator.IS_NOT_EMPTY
                    && filterStrategy == FilterStrategy.FEEDBACK_SCORES_IS_EMPTY) {
                        return Optional.empty();
                    } else
                if (filter.operator() == Operator.IS_EMPTY && isFeedBackScore(filter)) {
                    return Optional.empty();
                }

        return Optional.ofNullable(FILTER_STRATEGY_MAP.get(filterStrategy));
    }

    private static boolean isFeedBackScore(Filter filter) {
        Set<Field> feedbackScoreFields = Set.of(TraceField.FEEDBACK_SCORES, SpanField.FEEDBACK_SCORES,
                TraceThreadField.FEEDBACK_SCORES, ExperimentsComparisonValidKnownField.FEEDBACK_SCORES);
        return feedbackScoreFields.contains(filter.field());
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
            case DatasetItemField datasetItemField -> DATASET_ITEM_FIELDS_MAP.get(datasetItemField);
            case AnnotationQueueField annotationQueueField -> ANNOTATION_QUEUE_FIELDS_MAP.get(annotationQueueField);
            case AlertField alertField -> ALERT_FIELDS_MAP.get(alertField);
            case AutomationRuleEvaluatorField automationRuleEvaluatorField ->
                AUTOMATION_RULE_EVALUATOR_FIELDS_MAP.get(automationRuleEvaluatorField);
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

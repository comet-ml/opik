package com.comet.opik.domain.filter;

import com.comet.opik.api.filter.ExperimentsComparisonField;
import com.comet.opik.api.filter.Field;
import com.comet.opik.api.filter.FieldType;
import com.comet.opik.api.filter.Filter;
import com.comet.opik.api.filter.Operator;
import com.comet.opik.api.filter.SpanField;
import com.comet.opik.api.filter.TraceField;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import io.r2dbc.spi.Statement;
import lombok.NonNull;
import org.apache.commons.lang3.StringUtils;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;

public class FilterQueryBuilder {

    private static final String ANALYTICS_DB_AND_OPERATOR = "AND";

    private static final String JSONPATH_ROOT = "$.";

    private static final String ID_ANALYTICS_DB = "id";
    private static final String NAME_ANALYTICS_DB = "name";
    private static final String START_TIME_ANALYTICS_DB = "start_time";
    private static final String END_TIME_ANALYTICS_DB = "end_time";
    private static final String INPUT_ANALYTICS_DB = "input";
    private static final String OUTPUT_ANALYTICS_DB = "output";
    private static final String METADATA_ANALYTICS_DB = "metadata";
    private static final String EXPECTED_OUTPUT_ANALYTICS_DB = "expected_output";
    private static final String TAGS_ANALYTICS_DB = "tags";
    private static final String USAGE_COMPLETION_TOKENS_ANALYTICS_DB = "usage['completion_tokens']";
    private static final String USAGE_PROMPT_TOKENS_ANALYTICS_DB = "usage['prompt_tokens']";
    private static final String USAGE_TOTAL_TOKENS_ANALYTICS_DB = "usage['total_tokens']";
    private static final String VALUE_ANALYTICS_DB = "value";

    private static final Map<Operator, Map<FieldType, String>> ANALYTICS_DB_OPERATOR_MAP = new EnumMap<>(Map.of(
            Operator.CONTAINS, new EnumMap<>(Map.of(
                    FieldType.STRING, "ilike(%1$s, CONCAT('%%', :filter%2$d ,'%%'))",
                    FieldType.LIST,
                    "arrayExists(element -> (ilike(element, CONCAT('%%', :filter%2$d ,'%%'))), %1$s) = 1",
                    FieldType.DICTIONARY,
                    "ilike(JSON_VALUE(%1$s, :filterKey%2$d), CONCAT('%%', :filter%2$d ,'%%'))")),
            Operator.NOT_CONTAINS, new EnumMap<>(Map.of(
                    FieldType.STRING, "notILike(%1$s, CONCAT('%%', :filter%2$d ,'%%'))")),
            Operator.STARTS_WITH, new EnumMap<>(Map.of(
                    FieldType.STRING, "startsWith(lower(%1$s), lower(:filter%2$d))")),
            Operator.ENDS_WITH, new EnumMap<>(Map.of(
                    FieldType.STRING, "endsWith(lower(%1$s), lower(:filter%2$d))")),
            Operator.EQUAL, new EnumMap<>(Map.of(
                    FieldType.STRING, "lower(%1$s) = lower(:filter%2$d)",
                    FieldType.DATE_TIME, "%1$s = parseDateTime64BestEffort(:filter%2$d, 9)",
                    FieldType.NUMBER, "%1$s = :filter%2$d",
                    FieldType.FEEDBACK_SCORES_NUMBER,
                    "has(groupArray(tuple(lower(name), %1$s)), tuple(lower(:filterKey%2$d), toDecimal64(:filter%2$d, 9))) = 1",
                    FieldType.DICTIONARY,
                    "lower(JSON_VALUE(%1$s, :filterKey%2$d)) = lower(:filter%2$d)")),
            Operator.GREATER_THAN, new EnumMap<>(Map.of(
                    FieldType.DATE_TIME, "%1$s > parseDateTime64BestEffort(:filter%2$d, 9)",
                    FieldType.NUMBER, "%1$s > :filter%2$d",
                    FieldType.FEEDBACK_SCORES_NUMBER,
                    "arrayExists(element -> (element.1 = lower(:filterKey%2$d) AND element.2 > toDecimal64(:filter%2$d, 9)), groupArray(tuple(lower(name), %1$s))) = 1",
                    FieldType.DICTIONARY,
                    "toFloat64OrNull(JSON_VALUE(%1$s, :filterKey%2$d)) > toFloat64OrNull(:filter%2$d)")),
            Operator.GREATER_THAN_EQUAL, new EnumMap<>(Map.of(
                    FieldType.DATE_TIME, "%1$s >= parseDateTime64BestEffort(:filter%2$d, 9)",
                    FieldType.NUMBER, "%1$s >= :filter%2$d",
                    FieldType.FEEDBACK_SCORES_NUMBER,
                    "arrayExists(element -> (element.1 = lower(:filterKey%2$d) AND element.2 >= toDecimal64(:filter%2$d, 9)), groupArray(tuple(lower(name), %1$s))) = 1")),
            Operator.LESS_THAN, new EnumMap<>(Map.of(
                    FieldType.DATE_TIME, "%1$s < parseDateTime64BestEffort(:filter%2$d, 9)",
                    FieldType.NUMBER, "%1$s < :filter%2$d",
                    FieldType.FEEDBACK_SCORES_NUMBER,
                    "arrayExists(element -> (element.1 = lower(:filterKey%2$d) AND element.2 < toDecimal64(:filter%2$d, 9)), groupArray(tuple(lower(name), %1$s))) = 1",
                    FieldType.DICTIONARY,
                    "toFloat64OrNull(JSON_VALUE(%1$s, :filterKey%2$d)) < toFloat64OrNull(:filter%2$d)")),
            Operator.LESS_THAN_EQUAL, new EnumMap<>(Map.of(
                    FieldType.DATE_TIME, "%1$s <= parseDateTime64BestEffort(:filter%2$d, 9)",
                    FieldType.NUMBER, "%1$s <= :filter%2$d",
                    FieldType.FEEDBACK_SCORES_NUMBER,
                    "arrayExists(element -> (element.1 = lower(:filterKey%2$d) AND element.2 <= toDecimal64(:filter%2$d, 9)), groupArray(tuple(lower(name), %1$s))) = 1"))));

    private static final Map<TraceField, String> TRACE_FIELDS_MAP = new EnumMap<>(
            ImmutableMap.<TraceField, String>builder()
                    .put(TraceField.ID, ID_ANALYTICS_DB)
                    .put(TraceField.NAME, NAME_ANALYTICS_DB)
                    .put(TraceField.START_TIME, START_TIME_ANALYTICS_DB)
                    .put(TraceField.END_TIME, END_TIME_ANALYTICS_DB)
                    .put(TraceField.INPUT, INPUT_ANALYTICS_DB)
                    .put(TraceField.OUTPUT, OUTPUT_ANALYTICS_DB)
                    .put(TraceField.METADATA, METADATA_ANALYTICS_DB)
                    .put(TraceField.TAGS, TAGS_ANALYTICS_DB)
                    .put(TraceField.USAGE_COMPLETION_TOKENS, USAGE_COMPLETION_TOKENS_ANALYTICS_DB)
                    .put(TraceField.USAGE_PROMPT_TOKENS, USAGE_PROMPT_TOKENS_ANALYTICS_DB)
                    .put(TraceField.USAGE_TOTAL_TOKENS, USAGE_TOTAL_TOKENS_ANALYTICS_DB)
                    .put(TraceField.FEEDBACK_SCORES, VALUE_ANALYTICS_DB)
                    .build());

    private static final Map<SpanField, String> SPAN_FIELDS_MAP = new EnumMap<>(
            ImmutableMap.<SpanField, String>builder()
                    .put(SpanField.ID, ID_ANALYTICS_DB)
                    .put(SpanField.NAME, NAME_ANALYTICS_DB)
                    .put(SpanField.START_TIME, START_TIME_ANALYTICS_DB)
                    .put(SpanField.END_TIME, END_TIME_ANALYTICS_DB)
                    .put(SpanField.INPUT, INPUT_ANALYTICS_DB)
                    .put(SpanField.OUTPUT, OUTPUT_ANALYTICS_DB)
                    .put(SpanField.METADATA, METADATA_ANALYTICS_DB)
                    .put(SpanField.TAGS, TAGS_ANALYTICS_DB)
                    .put(SpanField.USAGE_COMPLETION_TOKENS, USAGE_COMPLETION_TOKENS_ANALYTICS_DB)
                    .put(SpanField.USAGE_PROMPT_TOKENS, USAGE_PROMPT_TOKENS_ANALYTICS_DB)
                    .put(SpanField.USAGE_TOTAL_TOKENS, USAGE_TOTAL_TOKENS_ANALYTICS_DB)
                    .put(SpanField.FEEDBACK_SCORES, VALUE_ANALYTICS_DB)
                    .build());

    private static final Map<ExperimentsComparisonField, String> EXPERIMENTS_COMPARISON_FIELDS_MAP = new EnumMap<>(
            ImmutableMap.<ExperimentsComparisonField, String>builder()
                    .put(ExperimentsComparisonField.OUTPUT, OUTPUT_ANALYTICS_DB)
                    .put(ExperimentsComparisonField.INPUT, INPUT_ANALYTICS_DB)
                    .put(ExperimentsComparisonField.EXPECTED_OUTPUT, EXPECTED_OUTPUT_ANALYTICS_DB)
                    .put(ExperimentsComparisonField.METADATA, METADATA_ANALYTICS_DB)
                    .put(ExperimentsComparisonField.FEEDBACK_SCORES, VALUE_ANALYTICS_DB)
                    .build());

    private static final Map<FilterStrategy, Set<? extends Field>> FILTER_STRATEGY_MAP = new EnumMap<>(Map.of(
            FilterStrategy.TRACE, EnumSet.copyOf(ImmutableSet.<TraceField>builder()
                    .add(TraceField.ID)
                    .add(TraceField.NAME)
                    .add(TraceField.START_TIME)
                    .add(TraceField.END_TIME)
                    .add(TraceField.INPUT)
                    .add(TraceField.OUTPUT)
                    .add(TraceField.METADATA)
                    .add(TraceField.TAGS)
                    .build()),
            FilterStrategy.TRACE_AGGREGATION, EnumSet.copyOf(ImmutableSet.<TraceField>builder()
                    .add(TraceField.USAGE_COMPLETION_TOKENS)
                    .add(TraceField.USAGE_PROMPT_TOKENS)
                    .add(TraceField.USAGE_TOTAL_TOKENS)
                    .build()),
            FilterStrategy.SPAN, EnumSet.copyOf(ImmutableSet.<SpanField>builder()
                    .add(SpanField.ID)
                    .add(SpanField.NAME)
                    .add(SpanField.START_TIME)
                    .add(SpanField.END_TIME)
                    .add(SpanField.INPUT)
                    .add(SpanField.OUTPUT)
                    .add(SpanField.METADATA)
                    .add(SpanField.TAGS)
                    .add(SpanField.USAGE_COMPLETION_TOKENS)
                    .add(SpanField.USAGE_PROMPT_TOKENS)
                    .add(SpanField.USAGE_TOTAL_TOKENS)
                    .build()),
            FilterStrategy.FEEDBACK_SCORES, ImmutableSet.<Field>builder()
                    .add(TraceField.FEEDBACK_SCORES)
                    .add(SpanField.FEEDBACK_SCORES)
                    .add(ExperimentsComparisonField.FEEDBACK_SCORES)
                    .build(),
            FilterStrategy.EXPERIMENT_ITEM, EnumSet.copyOf(ImmutableSet.<ExperimentsComparisonField>builder()
                    .add(ExperimentsComparisonField.OUTPUT)
                    .build()),
            FilterStrategy.DATASET_ITEM, EnumSet.copyOf(ImmutableSet.<ExperimentsComparisonField>builder()
                    .add(ExperimentsComparisonField.INPUT)
                    .add(ExperimentsComparisonField.EXPECTED_OUTPUT)
                    .add(ExperimentsComparisonField.METADATA)
                    .build())
    ));

    private static final Set<FieldType> KEY_SUPPORTED_FIELDS_SET = EnumSet.of(
            FieldType.DICTIONARY,
            FieldType.FEEDBACK_SCORES_NUMBER);

    public String toAnalyticsDbOperator(@NonNull Filter filter) {
        return ANALYTICS_DB_OPERATOR_MAP.get(filter.operator()).get(filter.field().getType());
    }

    public Optional<String> toAnalyticsDbFilters(
            @NonNull List<? extends Filter> filters, @NonNull FilterStrategy filterStrategy) {
        var stringJoiner = new StringJoiner(" %s ".formatted(ANALYTICS_DB_AND_OPERATOR));
        stringJoiner.setEmptyValue("");
        for (var i = 0; i < filters.size(); i++) {
            var filter = filters.get(i);
            if (FILTER_STRATEGY_MAP.get(filterStrategy).contains(filter.field())) {
                stringJoiner.add(toAnalyticsDbFilter(filter, i));
            }
        }
        var analyticsDbFilters = stringJoiner.toString();
        return StringUtils.isBlank(analyticsDbFilters)
                ? Optional.empty()
                : Optional.of("(%s)".formatted(analyticsDbFilters));
    }

    private String toAnalyticsDbFilter(Filter filter, int i) {
        var template = toAnalyticsDbOperator(filter);
        var formattedTemplate = template.formatted(getAnalyticsDbField(filter.field()), i);
        return "(%s)".formatted(formattedTemplate);
    }

    private String getAnalyticsDbField(Field field) {

        return switch (field) {
            case TraceField traceField -> TRACE_FIELDS_MAP.get(traceField);
            case SpanField spanField -> SPAN_FIELDS_MAP.get(spanField);
            case ExperimentsComparisonField experimentsComparisonField -> EXPERIMENTS_COMPARISON_FIELDS_MAP.get(experimentsComparisonField);
            default -> throw new IllegalArgumentException("Unknown type for field '%s', type '%s'".formatted(field, field.getClass()));
        };
    }

    public Statement bind(
            @NonNull Statement statement,
            @NonNull List<? extends Filter> filters,
            @NonNull FilterStrategy filterStrategy) {
        for (var i = 0; i < filters.size(); i++) {
            var filter = filters.get(i);
            if (FILTER_STRATEGY_MAP.get(filterStrategy).contains(filter.field())) {
                statement.bind("filter%d".formatted(i), filter.value());
                if (StringUtils.isNotBlank(filter.key())
                        && KEY_SUPPORTED_FIELDS_SET.contains(filter.field().getType())) {
                    var key = filter.key().startsWith(JSONPATH_ROOT) || filter.field().getType() != FieldType.DICTIONARY
                            ? filter.key()
                            : JSONPATH_ROOT + filter.key();
                    statement = statement.bind("filterKey%d".formatted(i), key);
                }
            }
        }
        return statement;
    }
}

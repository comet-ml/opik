package com.comet.opik.domain;

import com.comet.opik.api.ExperimentGroupAggregationItem;
import com.comet.opik.api.ExperimentGroupCriteria;
import com.comet.opik.api.ExperimentGroupItem;
import com.comet.opik.api.FeedbackScoreAverage;
import com.comet.opik.api.PercentageValues;
import com.comet.opik.domain.filter.FilterQueryBuilder;
import com.comet.opik.domain.filter.FilterStrategy;
import io.r2dbc.spi.Row;
import io.r2dbc.spi.Statement;
import org.apache.commons.collections4.CollectionUtils;
import org.stringtemplate.v4.ST;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;

import static com.comet.opik.utils.ValidationUtils.SCALE;

/**
 * Shared utilities for experiment group queries.
 * Used by both {@link ExperimentDAO} and
 * {@link com.comet.opik.domain.experiments.aggregations.ExperimentAggregatesDAO}.
 */
public final class ExperimentGroupMappers {

    private ExperimentGroupMappers() {
    }

    /**
     * Applies {@link ExperimentGroupCriteria} fields (name, types, filters, projectId, projectDeleted)
     * to a StringTemplate.
     * Shared by {@link ExperimentDAO} and
     * {@link com.comet.opik.domain.experiments.aggregations.ExperimentAggregatesDAO} to keep
     * group-query template wiring in one place.
     *
     * @param template           the ST template to populate
     * @param criteria           the group criteria
     * @param filterQueryBuilder the filter query builder for translating filter objects to SQL fragments
     */
    public static void applyGroupCriteriaToTemplate(ST template, ExperimentGroupCriteria criteria,
            FilterQueryBuilder filterQueryBuilder) {
        Optional.ofNullable(criteria.name())
                .ifPresent(name -> template.add("name", name));
        Optional.ofNullable(criteria.types())
                .filter(CollectionUtils::isNotEmpty)
                .ifPresent(types -> template.add("types", types));
        Optional.ofNullable(criteria.filters())
                .flatMap(filters -> filterQueryBuilder.toAnalyticsDbFilters(filters, FilterStrategy.EXPERIMENT))
                .ifPresent(experimentFilters -> template.add("filters", experimentFilters));
        Optional.ofNullable(criteria.projectId())
                .ifPresent(projectId -> template.add("project_id", projectId));
        Optional.ofNullable(criteria.projectDeleted())
                .ifPresent(projectDeleted -> template.add("project_deleted", projectDeleted));
    }

    public static void bindGroupCriteria(Statement statement, ExperimentGroupCriteria criteria,
            FilterQueryBuilder filterQueryBuilder) {
        Optional.ofNullable(criteria.name())
                .ifPresent(name -> statement.bind("name", name));
        Optional.ofNullable(criteria.types())
                .filter(CollectionUtils::isNotEmpty)
                .ifPresent(types -> statement.bind("types", types));
        Optional.ofNullable(criteria.filters())
                .ifPresent(filters -> filterQueryBuilder.bind(statement, filters, FilterStrategy.EXPERIMENT));
        Optional.ofNullable(criteria.projectId())
                .ifPresent(projectId -> statement.bind("project_id", projectId));
    }

    public static List<String> extractGroupValues(Row row, int groupsCount) {
        return IntStream.range(0, groupsCount)
                .mapToObj(i -> "group_" + i)
                .map(columnName -> row.get(columnName, String.class))
                .toList();
    }

    public static ExperimentGroupItem toExperimentGroupItem(Row row, int groupsCount) {
        return ExperimentGroupItem.builder()
                .groupValues(extractGroupValues(row, groupsCount))
                .lastCreatedExperimentAt(row.get("last_created_experiment_at", Instant.class))
                .build();
    }

    public static ExperimentGroupAggregationItem toExperimentGroupAggregationItem(Row row, int groupsCount) {
        return ExperimentGroupAggregationItem.builder()
                .groupValues(extractGroupValues(row, groupsCount))
                .experimentCount(row.get("experiment_count", Long.class))
                .traceCount(row.get("trace_count", Long.class))
                .totalEstimatedCost(getCostValue(row, "total_estimated_cost"))
                .totalEstimatedCostAvg(getCostValue(row, "total_estimated_cost_avg"))
                .duration(getDuration(row))
                .feedbackScores(ExperimentDAO.getFeedbackScores(row, "feedback_scores"))
                .experimentScores(ExperimentDAO.getFeedbackScores(row, "experiment_scores"))
                .build();
    }

    public static BigDecimal getCostValue(Row row, String fieldName) {
        return Optional.ofNullable(row.get(fieldName, BigDecimal.class))
                .filter(value -> value.compareTo(BigDecimal.ZERO) > 0)
                .orElse(null);
    }

    public static PercentageValues getDuration(Row row) {
        return Optional.ofNullable(row.get("duration", Map.class))
                .map(map -> (Map<String, ? extends Number>) map)
                .map(durations -> new PercentageValues(
                        convertToBigDecimal(durations.get("p50")),
                        convertToBigDecimal(durations.get("p90")),
                        convertToBigDecimal(durations.get("p99"))))
                .orElse(null);
    }

    private static BigDecimal convertToBigDecimal(Number value) {
        if (value instanceof BigDecimal bd) {
            return bd;
        } else if (value instanceof Double d) {
            return BigDecimal.valueOf(d);
        } else {
            return null;
        }
    }

    static List<FeedbackScoreAverage> getFeedbackScoreAverages(Row row, String columnName) {
        List<FeedbackScoreAverage> scoresAvg = Optional
                .ofNullable(row.get(columnName, Map.class))
                .map(map -> (Map<String, ? extends Number>) map)
                .orElse(Map.of())
                .entrySet()
                .stream()
                .map(scores -> new FeedbackScoreAverage(scores.getKey(),
                        BigDecimal.valueOf(scores.getValue().doubleValue()).setScale(SCALE, RoundingMode.HALF_EVEN)))
                .toList();

        return scoresAvg.isEmpty() ? null : scoresAvg;
    }
}

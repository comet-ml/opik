package com.comet.opik.domain.experiments.aggregations;

import com.comet.opik.api.DatasetItem.DatasetItemPage;
import com.comet.opik.api.EvaluationMethod;
import com.comet.opik.api.Experiment;
import com.comet.opik.api.ExperimentGroupAggregationItem;
import com.comet.opik.api.ExperimentGroupCriteria;
import com.comet.opik.api.ExperimentGroupItem;
import com.comet.opik.api.ExperimentScore;
import com.comet.opik.api.ExperimentSearchCriteria;
import com.comet.opik.api.ExperimentStatus;
import com.comet.opik.api.ExperimentType;
import com.comet.opik.api.FeedbackScore;
import com.comet.opik.api.FeedbackScoreAverage;
import com.comet.opik.api.PercentageValues;
import com.comet.opik.api.ProjectStats;
import com.comet.opik.api.VisibilityMode;
import com.comet.opik.api.filter.ExperimentsComparisonFilter;
import com.comet.opik.domain.CommentResultMapper;
import com.comet.opik.domain.DatasetItemResultMapper;
import com.comet.opik.domain.DatasetItemSearchCriteria;
import com.comet.opik.domain.DatasetItemSearchCriteriaMapper;
import com.comet.opik.domain.ExperimentGroupMappers;
import com.comet.opik.domain.ExperimentSearchCriteriaBinder;
import com.comet.opik.domain.FeedbackScoreMapper;
import com.comet.opik.domain.GroupingQueryBuilder;
import com.comet.opik.domain.experiments.aggregations.ExperimentAggregatesUtils.MapArrays;
import com.comet.opik.domain.experiments.aggregations.ExperimentEntityData.ExperimentItemData;
import com.comet.opik.domain.experiments.aggregations.ExperimentSourceData.SpanData;
import com.comet.opik.domain.experiments.aggregations.ExperimentSourceData.TraceData;
import com.comet.opik.domain.filter.FilterQueryBuilder;
import com.comet.opik.domain.filter.FilterStrategy;
import com.comet.opik.domain.stats.StatsMapper;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.db.TransactionTemplateAsync;
import com.comet.opik.utils.JsonUtils;
import com.comet.opik.utils.template.TemplateUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.inject.ImplementedBy;
import io.r2dbc.spi.Connection;
import io.r2dbc.spi.Result;
import io.r2dbc.spi.Row;
import io.r2dbc.spi.Statement;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.stringtemplate.v4.ST;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.stream.Collectors;

import static com.comet.opik.domain.AsyncContextUtils.bindWorkspaceIdToFlux;
import static com.comet.opik.domain.ExperimentGroupMappers.bindGroupCriteria;
import static com.comet.opik.domain.experiments.aggregations.ExperimentAggregatesModel.FeedbackScoreAggregations;
import static com.comet.opik.domain.experiments.aggregations.ExperimentAggregatesModel.PassRateAggregation;
import static com.comet.opik.domain.experiments.aggregations.ExperimentAggregatesModel.SpanAggregations;
import static com.comet.opik.domain.experiments.aggregations.ExperimentAggregatesModel.TraceAggregations;
import static com.comet.opik.domain.experiments.aggregations.ExperimentAggregatesUtils.BatchResult;
import static com.comet.opik.domain.experiments.aggregations.ExperimentEntityData.ExperimentData;
import static com.comet.opik.domain.experiments.aggregations.ExperimentSourceData.CommentsData;
import static com.comet.opik.domain.experiments.aggregations.ExperimentSourceData.FeedbackScoreData;
import static com.comet.opik.infrastructure.DatabaseUtils.getSTWithLogComment;
import static com.comet.opik.utils.AsyncUtils.makeFluxContextAware;
import static com.comet.opik.utils.AsyncUtils.makeMonoContextAware;
import static com.comet.opik.utils.template.TemplateUtils.getQueryItemPlaceHolder;

@ImplementedBy(ExperimentAggregatesDAOImpl.class)
public interface ExperimentAggregatesDAO {

    Mono<Void> populateExperimentAggregate(@NonNull UUID experimentId);

    Mono<BatchResult> populateExperimentItemAggregates(UUID experimentId, UUID cursor, int limit);

    Mono<Experiment> getExperimentFromAggregates(UUID experimentId);

    Mono<Long> countTotal(ExperimentSearchCriteria experimentSearchCriteria);

    Flux<ExperimentGroupItem> findGroups(ExperimentGroupCriteria criteria);

    Flux<ExperimentGroupAggregationItem> findGroupsAggregations(ExperimentGroupCriteria criteria);

    Mono<Long> countDatasetItemsWithExperimentItemsFromAggregates(@NonNull DatasetItemSearchCriteria criteria,
            @NonNull UUID versionId);

    Mono<DatasetItemPage> getDatasetItemsWithExperimentItemsFromAggregates(
            @NonNull DatasetItemSearchCriteria criteria,
            @NonNull UUID versionId, int page, int size);

    Mono<ProjectStats> getExperimentItemsStatsFromAggregates(@NonNull UUID datasetId,
            @NonNull UUID versionId, @NonNull Set<UUID> experimentIds,
            List<ExperimentsComparisonFilter> filters);

    Mono<AggregatedExperimentCounts> getAggregationBranchCounts(
            @NonNull AggregationBranchCountsCriteria criteria);
}

@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Slf4j
class ExperimentAggregatesDAOImpl implements ExperimentAggregatesDAO {

    private static final TypeReference<List<ExperimentScore>> TYPE_REFERENCE = new TypeReference<>() {
    };

    private static final String EMPTY_ARRAY_STR = "[]";

    private final @NonNull TransactionTemplateAsync asyncTemplate;
    private final @NonNull FilterQueryBuilder filterQueryBuilder;
    private final @NonNull GroupingQueryBuilder groupingQueryBuilder;

    /**
     * Filter strategies used for experiment aggregates search binding.
     * Reused across all experiment search operations to avoid repeated allocations.
     */
    private static final List<FilterStrategy> FILTER_STRATEGIES = List.of(
            FilterStrategy.EXPERIMENT,
            FilterStrategy.FEEDBACK_SCORES_AGGREGATED,
            FilterStrategy.FEEDBACK_SCORES_AGGREGATED_IS_EMPTY,
            FilterStrategy.EXPERIMENT_SCORES,
            FilterStrategy.EXPERIMENT_SCORES_IS_EMPTY);

    private static final List<FilterQueryBuilder.FilterStrategyParam> DATASET_ITEM_FILTER_STRATEGY_PARAMS = List.of(
            new FilterQueryBuilder.FilterStrategyParam(FilterStrategy.EXPERIMENT_ITEM, "experiment_item_filters"),
            new FilterQueryBuilder.FilterStrategyParam(FilterStrategy.FEEDBACK_SCORES_AGGREGATED,
                    "feedback_scores_filters"),
            new FilterQueryBuilder.FilterStrategyParam(FilterStrategy.FEEDBACK_SCORES_AGGREGATED_IS_EMPTY,
                    "feedback_scores_empty_filters"),
            new FilterQueryBuilder.FilterStrategyParam(FilterStrategy.DATASET_ITEM, "dataset_item_filters"));

    private static final List<FilterStrategy> DATASET_ITEM_BIND_STRATEGIES = List.of(
            FilterStrategy.EXPERIMENT_ITEM,
            FilterStrategy.FEEDBACK_SCORES_AGGREGATED,
            FilterStrategy.FEEDBACK_SCORES_AGGREGATED_IS_EMPTY,
            FilterStrategy.DATASET_ITEM);

    private static final List<FilterQueryBuilder.FilterStrategyParam> EXPERIMENT_ITEMS_STATS_FILTER_STRATEGY_PARAMS = List
            .of(
                    new FilterQueryBuilder.FilterStrategyParam(FilterStrategy.EXPERIMENT_ITEM,
                            "experiment_item_filters"),
                    new FilterQueryBuilder.FilterStrategyParam(FilterStrategy.FEEDBACK_SCORES,
                            "feedback_scores_filters"),
                    new FilterQueryBuilder.FilterStrategyParam(FilterStrategy.FEEDBACK_SCORES_AGGREGATED_IS_EMPTY,
                            "feedback_scores_empty_filters"),
                    new FilterQueryBuilder.FilterStrategyParam(FilterStrategy.DATASET_ITEM, "dataset_item_filters"));

    private static final List<FilterStrategy> EXPERIMENT_ITEMS_STATS_BIND_STRATEGIES = List.of(
            FilterStrategy.EXPERIMENT_ITEM,
            FilterStrategy.FEEDBACK_SCORES,
            FilterStrategy.FEEDBACK_SCORES_AGGREGATED_IS_EMPTY,
            FilterStrategy.DATASET_ITEM);

    public static final String SELECT_EXPERIMENT_BY_ID = """
            SELECT
                id,
                dataset_id,
                project_id,
                name,
                created_at,
                last_updated_at,
                created_by,
                last_updated_by,
                metadata,
                tags,
                type,
                evaluation_method,
                status,
                optimization_id,
                dataset_version_id,
                prompt_versions,
                experiment_scores,
                trace_count,
                duration_percentiles,
                feedback_scores_percentiles,
                feedback_scores_avg,
                total_estimated_cost_sum,
                total_estimated_cost_avg,
                usage_avg,
                pass_rate,
                passed_count,
                total_count,
                comments_array_agg
            FROM experiment_aggregates FINAL
            WHERE workspace_id = :workspace_id
            AND id = :experiment_id
            SETTINGS log_comment = '<log_comment>'
            ;
            """;

    /**
     * Fetch experiment base data
     */
    private static final String GET_EXPERIMENT_DATA = """
            SELECT
                workspace_id,
                id,
                dataset_id,
                name,
                created_at,
                last_updated_at,
                created_by,
                last_updated_by,
                metadata,
                prompt_versions,
                optimization_id,
                dataset_version_id,
                tags,
                type,
                evaluation_method,
                status,
                experiment_scores
            FROM experiments
            WHERE workspace_id = :workspace_id
            AND id = :experiment_id
            SETTINGS log_comment = '<log_comment>'
            ;
            """;

    /**
     * Get project_id from first experiment item trace
     */
    private static final String GET_PROJECT_ID = """
            WITH experiment_trace_items AS (
                SELECT DISTINCT trace_id
                FROM experiment_items
                WHERE workspace_id = :workspace_id
                AND experiment_id = :experiment_id
            )
            SELECT DISTINCT project_id
            FROM traces
            INNER JOIN experiment_trace_items ON traces.id = experiment_trace_items.trace_id
            WHERE workspace_id = :workspace_id
            LIMIT 1
            SETTINGS log_comment = '<log_comment>'
            ;
            """;

    /**
     * Fetch trace aggregations for an experiment
     */
    private static final String GET_TRACE_AGGREGATIONS = """
            WITH experiment_trace_items AS (
                SELECT DISTINCT trace_id
                FROM experiment_items
                WHERE workspace_id = :workspace_id
                AND experiment_id = :experiment_id
            ), traces_data AS (
                SELECT
                    id,
                    duration
                FROM traces
                INNER JOIN experiment_trace_items ON traces.id = experiment_trace_items.trace_id
                WHERE workspace_id = :workspace_id
                AND project_id = :project_id
                ORDER BY (workspace_id, project_id, id) DESC, last_updated_at DESC
                LIMIT 1 by id
            )
            SELECT
                :experiment_id as experiment_id,
                :project_id as project_id,
                mapFromArrays(
                    ['p50', 'p90', 'p99'],
                    arrayMap(
                        v -> toDecimal64(
                            greatest(
                                least(if(isFinite(toFloat64(v)), v, 0), 999999999.999999999),
                                -999999999.999999999
                            ),
                            9
                        ),
                        quantiles(0.5, 0.9, 0.99)(duration)
                    )
                ) AS duration_percentiles,
                count(DISTINCT id) as trace_count
            FROM traces_data
            SETTINGS log_comment = '<log_comment>'
            ;
            """;

    /**
     * Fetch span aggregations for an experiment
     */
    private static final String GET_SPAN_AGGREGATIONS = """
            WITH experiment_items AS (
                SELECT DISTINCT trace_id
                FROM experiment_items
                WHERE workspace_id = :workspace_id
                AND experiment_id = :experiment_id
            ), spans_data AS (
                SELECT
                    trace_id,
                    usage,
                    total_estimated_cost
                FROM spans FINAL
                WHERE workspace_id = :workspace_id
                AND project_id = :project_id
                AND trace_id IN (SELECT trace_id FROM experiment_items)
            ), spans_agg AS (
                SELECT
                    trace_id,
                    sumMap(usage) as usage,
                    sum(total_estimated_cost) as total_estimated_cost
                FROM spans_data
                GROUP BY trace_id
            ), usage_total_tokens_data AS (
                SELECT
                    usage['total_tokens'] AS total_tokens
                FROM spans_agg
                WHERE usage['total_tokens'] IS NOT NULL AND usage['total_tokens'] > 0
            )
            SELECT
                :experiment_id as experiment_id,
                avgMap(usage) as usage_avg,
                coalesce(sum(total_estimated_cost), 0.0) as total_estimated_cost_sum,
                coalesce(avg(total_estimated_cost), 0.0) as total_estimated_cost_avg,
                mapFromArrays(
                    ['p50', 'p90', 'p99'],
                    arrayMap(
                        v -> toDecimal128(
                            greatest(
                                least(if(isFinite(toFloat64(v)), v, 0), 999999999999.999999999999),
                                -999999999999.999999999999
                            ),
                            12
                        ),
                        quantiles(0.5, 0.9, 0.99)(total_estimated_cost)
                    )
                ) AS total_estimated_cost_percentiles,
                mapFromArrays(
                    ['p50', 'p90', 'p99'],
                    arrayMap(
                        v -> toInt64(greatest(least(if(isFinite(toFloat64(v)), v, 0), 999999999.999999999), -999999999.999999999)),
                        (SELECT quantiles(0.5, 0.9, 0.99)(total_tokens) FROM usage_total_tokens_data)
                    )
                ) AS usage_total_tokens_percentiles
            FROM spans_agg
            SETTINGS log_comment = '<log_comment>'
            ;
            """;

    /**
     * Fetch feedback score aggregations for an experiment
     */
    private static final String GET_FEEDBACK_SCORE_AGGREGATIONS = """
            WITH experiment_items AS (
                SELECT DISTINCT trace_id
                FROM experiment_items
                WHERE workspace_id = :workspace_id
                AND experiment_id = :experiment_id
            ), feedback_scores_combined AS (
                SELECT
                    entity_id,
                    name,
                    value
                FROM feedback_scores FINAL
                WHERE entity_type = 'trace'
                AND workspace_id = :workspace_id
                AND project_id = :project_id
                UNION ALL
                SELECT
                    entity_id,
                    name,
                    value
                FROM authored_feedback_scores FINAL
                WHERE entity_type = 'trace'
                AND workspace_id = :workspace_id
                AND project_id = :project_id
            ), feedback_scores_final AS (
                SELECT
                    entity_id,
                    name,
                    if(count() = 1, any(value), toDecimal64(avg(value), 9)) AS value
                FROM feedback_scores_combined
                INNER JOIN experiment_items ON feedback_scores_combined.entity_id = experiment_items.trace_id
                GROUP BY entity_id, name
            ), feedback_percentiles AS (
                SELECT
                    name,
                    mapFromArrays(
                        ['p50', 'p90', 'p99'],
                        arrayMap(
                            v -> toDecimal64(
                                greatest(
                                    least(if(isFinite(toFloat64(v)), v, 0), 999999999.999999999),
                                    -999999999.999999999
                                ),
                                9
                            ),
                            quantiles(0.5, 0.9, 0.99)(value)
                        )
                    ) AS percentiles
                FROM feedback_scores_final
                WHERE length(name) > 0
                GROUP BY name
            ), feedback_avg AS (
                SELECT
                    name,
                    toDecimal64(
                        greatest(
                            least(if(isFinite(avg(value)), avg(value), 0), 999999999.999999999),
                            -999999999.999999999
                        ),
                        9
                    ) AS avg_value
                FROM feedback_scores_final
                WHERE length(name) > 0
                GROUP BY name
            )
            SELECT
                :experiment_id as experiment_id,
                (SELECT mapFromArrays(groupArray(name), groupArray(percentiles)) FROM feedback_percentiles) AS feedback_scores_percentiles,
                (SELECT mapFromArrays(groupArray(name), groupArray(avg_value)) FROM feedback_avg) AS feedback_scores_avg
            SETTINGS log_comment = '<log_comment>'
            ;
            """;

    private static final String GET_PASS_RATE_AGGREGATION = """
            WITH experiment_items_scope AS (
                SELECT DISTINCT dataset_item_id, trace_id, execution_policy, id, experiment_id
                FROM experiment_items FINAL
                WHERE workspace_id = :workspace_id
                AND experiment_id = :experiment_id
            ), experiment_data AS (
                SELECT execution_policy, id
                FROM experiments FINAL
                WHERE workspace_id = :workspace_id
                AND id = :experiment_id
                AND evaluation_method = 'evaluation_suite'
            ), feedback_scores_combined AS (
                SELECT
                    entity_id,
                    name,
                    value
                FROM feedback_scores
                WHERE entity_type = 'trace'
                AND workspace_id = :workspace_id
                AND project_id = :project_id
                UNION ALL
                SELECT
                    entity_id,
                    name,
                    value
                FROM authored_feedback_scores
                WHERE entity_type = 'trace'
                AND workspace_id = :workspace_id
                AND project_id = :project_id
            ), feedback_scores_final AS (
                SELECT
                    entity_id,
                    name,
                    if(count() = 1, any(value), toDecimal64(avg(value), 9)) AS value
                FROM feedback_scores_combined fc
                INNER JOIN experiment_items_scope ei ON fc.entity_id = ei.trace_id
                GROUP BY entity_id, name
            ), runs AS (
                SELECT
                    ei.dataset_item_id,
                    ei.trace_id,
                    JSONExtractUInt(ei.execution_policy, 'pass_threshold') AS item_pass_threshold,
                    JSONExtractUInt(ed.execution_policy, 'pass_threshold') AS suite_pass_threshold,
                    if(
                        countIf(fs.name != '') = 0,
                        1,
                        if(minIf(fs.value, fs.name != '') >= 1.0, 1, 0)
                    ) AS run_passed
                FROM experiment_items_scope ei
                INNER JOIN experiment_data ed ON ei.experiment_id = ed.id
                LEFT JOIN feedback_scores_final fs ON fs.entity_id = ei.trace_id
                GROUP BY ei.dataset_item_id, ei.trace_id,
                         item_pass_threshold, suite_pass_threshold
            ), items AS (
                SELECT
                    dataset_item_id,
                    if(sum(run_passed) >=
                       if(item_pass_threshold > 0, item_pass_threshold,
                          if(suite_pass_threshold > 0, suite_pass_threshold, 1)),
                       1, 0) AS item_passed
                FROM runs
                GROUP BY dataset_item_id, item_pass_threshold, suite_pass_threshold
            )
            SELECT
                :experiment_id as experiment_id,
                toDecimal64(if(count(*) = 0, 0, sum(item_passed) / count(*)), 9) AS pass_rate,
                sum(item_passed) AS passed_count,
                count(*) AS total_count
            FROM items
            SETTINGS log_comment = '<log_comment>'
            ;
            """;

    /**
     * Insert experiment aggregate
     */
    private static final String INSERT_EXPERIMENT_AGGREGATE = """
            INSERT INTO experiment_aggregates
            (
                workspace_id,
                id,
                dataset_id,
                project_id,
                name,
                created_at,
                last_updated_at,
                created_by,
                last_updated_by,
                metadata,
                prompt_versions,
                optimization_id,
                dataset_version_id,
                tags,
                type,
                evaluation_method,
                status,
                experiment_scores,
                trace_count,
                experiment_items_count,
                duration_percentiles,
                feedback_scores_percentiles,
                feedback_scores_avg,
                total_estimated_cost_sum,
                total_estimated_cost_avg,
                total_estimated_cost_percentiles,
                usage_avg,
                usage_total_tokens_percentiles,
                pass_rate,
                passed_count,
                total_count,
                comments_array_agg
            )
            SETTINGS log_comment = '<log_comment>'
            VALUES (
                :workspace_id,
                :id,
                :dataset_id,
                :project_id,
                :name,
                :created_at,
                :last_updated_at,
                :created_by,
                :last_updated_by,
                :metadata,
                :prompt_versions,
                :optimization_id,
                :dataset_version_id,
                :tags,
                :type,
                :evaluation_method,
                :status,
                mapFromArrays(:experiment_scores_keys, :experiment_scores_values),
                :trace_count,
                :experiment_items_count,
                mapFromArrays(:duration_percentiles_keys, :duration_percentiles_values),
                :feedback_scores_percentiles,
                mapFromArrays(:feedback_scores_avg_keys, :feedback_scores_avg_values),
                :total_estimated_cost_sum,
                :total_estimated_cost_avg,
                mapFromArrays(:total_estimated_cost_percentiles_keys, :total_estimated_cost_percentiles_values),
                mapFromArrays(:usage_avg_keys, :usage_avg_values),
                mapFromArrays(:usage_total_tokens_percentiles_keys, :usage_total_tokens_percentiles_values),
                :pass_rate,
                :passed_count,
                :total_count,
                :comments_array_agg
            )
            ;
            """;

    /**
     * Get experiment items with cursor pagination
     */
    private static final String GET_EXPERIMENT_ITEMS = """
            SELECT
                id,
                experiment_id,
                trace_id,
                dataset_item_id,
                created_at,
                last_updated_at,
                created_by,
                last_updated_by
            FROM experiment_items FINAL
            WHERE workspace_id = :workspace_id
            AND experiment_id = :experiment_id
            <if(cursor)>AND id > :cursor<endif>
            ORDER BY id ASC
            LIMIT :limit
            SETTINGS log_comment = '<log_comment>'
            ;
            """;

    /**
     * Get trace data for experiment items with cursor
     */
    private static final String GET_TRACES_DATA = """
            WITH experiment_items AS (
                SELECT DISTINCT trace_id
                FROM experiment_items FINAL
                WHERE workspace_id = :workspace_id
                AND experiment_id = :experiment_id
                <if(cursor)>AND id > :cursor<endif>
                ORDER BY id ASC
                LIMIT :limit
            )
            SELECT
                id as trace_id,
                project_id,
                duration,
                metadata,
                input,
                output,
                input_slim,
                output_slim,
                visibility_mode
            FROM traces FINAL
            WHERE workspace_id = :workspace_id
            AND project_id = :project_id
            AND id IN (SELECT trace_id FROM experiment_items)
            SETTINGS log_comment = '<log_comment>'
            ;
            """;

    /**
     * Get span data for experiment items with cursor
     */
    private static final String GET_SPANS_DATA = """
            WITH experiment_items AS (
                SELECT DISTINCT trace_id
                FROM experiment_items FINAL
                WHERE workspace_id = :workspace_id
                AND experiment_id = :experiment_id
                <if(cursor)>AND id > :cursor<endif>
                ORDER BY id ASC
                LIMIT :limit
            )
            SELECT
                trace_id,
                sumMap(usage) as usage,
                sum(total_estimated_cost) as total_estimated_cost
            FROM spans FINAL
            WHERE workspace_id = :workspace_id
            AND project_id = :project_id
            AND trace_id IN (SELECT trace_id FROM experiment_items)
            GROUP BY trace_id
            SETTINGS log_comment = '<log_comment>'
            ;
            """;

    /**
     * Get feedback scores for experiment items with cursor
     */
    private static final String GET_FEEDBACK_SCORES_DATA = """
            WITH experiment_items AS (
                SELECT DISTINCT trace_id
                FROM experiment_items FINAL
                WHERE workspace_id = :workspace_id
                AND experiment_id = :experiment_id
                <if(cursor)>AND id > :cursor<endif>
                ORDER BY id ASC
                LIMIT :limit
            ), feedback_scores_combined_raw AS (
                SELECT
                    workspace_id,
                    project_id,
                    entity_id,
                    name,
                    category_name,
                    value,
                    reason,
                    source,
                    created_by,
                    last_updated_by,
                    created_at,
                    last_updated_at,
                    feedback_scores.last_updated_by AS author
                FROM feedback_scores FINAL
                WHERE entity_type = 'trace'
                AND workspace_id = :workspace_id
                AND project_id = :project_id
                AND entity_id IN (SELECT trace_id FROM experiment_items)
                UNION ALL
                SELECT
                    workspace_id,
                    project_id,
                    entity_id,
                    name,
                    category_name,
                    value,
                    reason,
                    source,
                    created_by,
                    last_updated_by,
                    created_at,
                    last_updated_at,
                    author
                FROM authored_feedback_scores FINAL
                WHERE entity_type = 'trace'
                AND workspace_id = :workspace_id
                AND project_id = :project_id
                AND entity_id IN (SELECT trace_id FROM experiment_items)
            ), feedback_scores_with_ranking AS (
                SELECT workspace_id,
                       project_id,
                       entity_id,
                       name,
                       category_name,
                       value,
                       reason,
                       source,
                       created_by,
                       last_updated_by,
                       created_at,
                       last_updated_at,
                       author,
                       ROW_NUMBER() OVER (
                           PARTITION BY workspace_id, project_id, entity_id, name, author
                           ORDER BY last_updated_at DESC
                       ) as rn
                FROM feedback_scores_combined_raw
            ), feedback_scores_combined AS (
                SELECT
                    workspace_id,
                    project_id,
                    entity_id,
                    name,
                    category_name,
                    value,
                    reason,
                    source,
                    created_by,
                    last_updated_by,
                    created_at,
                    last_updated_at,
                    author
                FROM feedback_scores_with_ranking
                WHERE rn = 1
            ), feedback_scores_combined_grouped AS (
                SELECT
                    workspace_id,
                    project_id,
                    entity_id,
                    name,
                    groupArray(value) AS values,
                    groupArray(reason) AS reasons,
                    groupArray(category_name) AS categories,
                    groupArray(author) AS authors,
                    groupArray(source) AS sources,
                    groupArray(created_by) AS created_bies,
                    groupArray(last_updated_by) AS updated_bies,
                    groupArray(created_at) AS created_ats,
                    groupArray(last_updated_at) AS last_updated_ats
                FROM feedback_scores_combined
                GROUP BY workspace_id, project_id, entity_id, name
            ), feedback_scores_final AS (
                SELECT
                    workspace_id,
                    project_id,
                    entity_id,
                    name,
                    arrayStringConcat(categories, ', ') AS category_name,
                    IF(length(values) = 1, arrayElement(values, 1), toDecimal64(arrayAvg(values), 9)) AS value,
                    IF(length(reasons) = 1, arrayElement(reasons, 1), arrayStringConcat(arrayMap(x -> if(x = '', '\\<no reason>', x), reasons), ', ')) AS reason,
                    arrayElement(sources, 1) AS source,
                    mapFromArrays(
                        authors,
                        arrayMap(
                            i -> tuple(values[i], reasons[i], categories[i], sources[i], last_updated_ats[i]),
                            arrayEnumerate(values)
                        )
                    ) AS value_by_author,
                    arrayStringConcat(created_bies, ', ') AS created_by,
                    arrayStringConcat(updated_bies, ', ') AS last_updated_by,
                    arrayMin(created_ats) AS created_at,
                    arrayMax(last_updated_ats) AS last_updated_at
                FROM feedback_scores_combined_grouped
            )
            SELECT
                entity_id as trace_id,
                mapFromArrays(
                    groupArray(name),
                    groupArray(value)
                ) AS feedback_scores,
                groupUniqArray(tuple(
                    entity_id,
                    name,
                    category_name,
                    value,
                    reason,
                    source,
                    created_at,
                    last_updated_at,
                    created_by,
                    last_updated_by,
                    value_by_author
                )) AS feedback_scores_array
            FROM feedback_scores_final
            GROUP BY entity_id
            SETTINGS log_comment = '<log_comment>'
            ;
            """;

    /**
     * Get comments for experiment items with cursor
     */
    private static final String GET_COMMENTS_DATA = """
            WITH experiment_items AS (
                SELECT DISTINCT trace_id
                FROM experiment_items FINAL
                WHERE workspace_id = :workspace_id
                AND experiment_id = :experiment_id
                <if(cursor)>AND id > :cursor<endif>
                ORDER BY id ASC
                LIMIT :limit
            )
            SELECT
                entity_id AS trace_id,
                toJSONString(groupUniqArray(CAST(tuple(
                    id,
                    text,
                    concat(replaceOne(toString(created_at), ' ', 'T'), 'Z'),
                    concat(replaceOne(toString(last_updated_at), ' ', 'T'), 'Z'),
                    created_by,
                    last_updated_by,
                    entity_id
                ), 'Tuple(
                    id FixedString(36),
                    text String,
                    created_at String,
                    last_updated_at String,
                    created_by String,
                    last_updated_by String,
                    entity_id FixedString(36)
                )'))) AS comments_array_agg
            FROM (
                SELECT
                    id,
                    text,
                    created_at,
                    last_updated_at,
                    created_by,
                    last_updated_by,
                    entity_id
                FROM comments
                WHERE workspace_id = :workspace_id
                AND project_id = :project_id
                AND entity_id IN (SELECT trace_id FROM experiment_items)
                ORDER BY (workspace_id, project_id, entity_id, id) DESC, last_updated_at DESC
                LIMIT 1 BY id
            )
            GROUP BY entity_id
            SETTINGS log_comment = '<log_comment>'
            ;
            """;

    /**
     * Get comments aggregated at experiment level
     */
    private static final String GET_COMMENTS_AGGREGATION = """
            WITH experiment_items AS (
                SELECT DISTINCT trace_id
                FROM experiment_items FINAL
                WHERE workspace_id = :workspace_id
                AND experiment_id = :experiment_id
            )
            SELECT
                :experiment_id AS experiment_id,
                toJSONString(groupUniqArrayArray(comments_array)) AS comments_array_agg
            FROM (
                SELECT
                    entity_id,
                    groupArray(CAST(tuple(
                        id,
                        text,
                        concat(replaceOne(toString(created_at), ' ', 'T'), 'Z'),
                        concat(replaceOne(toString(last_updated_at), ' ', 'T'), 'Z'),
                        created_by,
                        last_updated_by,
                        entity_id
                    ), 'Tuple(
                        id FixedString(36),
                        text String,
                        created_at String,
                        last_updated_at String,
                        created_by String,
                        last_updated_by String,
                        entity_id FixedString(36)
                    )')) AS comments_array
                FROM (
                    SELECT
                        id,
                        text,
                        created_at,
                        last_updated_at,
                        created_by,
                        last_updated_by,
                        entity_id
                    FROM comments
                    WHERE workspace_id = :workspace_id
                    AND project_id = :project_id
                    AND entity_id IN (SELECT trace_id FROM experiment_items)
                    ORDER BY (workspace_id, project_id, entity_id, id) DESC, last_updated_at DESC
                    LIMIT 1 BY id
                )
                GROUP BY entity_id
            )
            SETTINGS log_comment = '<log_comment>'
            ;
            """;

    /**
     * Insert experiment item aggregate
     */
    private static final String INSERT_EXPERIMENT_ITEM_AGGREGATE = """
            INSERT INTO experiment_item_aggregates
            (
                workspace_id,
                id,
                project_id,
                experiment_id,
                dataset_item_id,
                trace_id,
                input,
                output,
                input_slim,
                output_slim,
                metadata,
                duration,
                total_estimated_cost,
                usage,
                feedback_scores,
                feedback_scores_array,
                comments_array_agg,
                visibility_mode,
                created_at,
                last_updated_at,
                created_by,
                last_updated_by
            )
            SETTINGS log_comment = '<log_comment>'
            FORMAT Values
            <items:{item |
                (
                    :workspace_id,
                    :id<item.index>,
                    :project_id,
                    :experiment_id<item.index>,
                    :dataset_item_id<item.index>,
                    :trace_id<item.index>,
                    :input<item.index>,
                    :output<item.index>,
                    :input_slim<item.index>,
                    :output_slim<item.index>,
                    :metadata<item.index>,
                    :duration<item.index>,
                    :total_estimated_cost<item.index>,
                    if(:has_usage<item.index>, mapFromArrays(:usage_keys<item.index>, :usage_values<item.index>), CAST(map() AS Map(String, Int64)) ),
                    if(:has_feedback_scores<item.index>, mapFromArrays(:feedback_scores_keys<item.index>, CAST(:feedback_scores_values<item.index> AS Array(Decimal64(9)))), CAST(map() AS Map(String, Decimal64(9))) ),
                    :feedback_scores_array<item.index>,
                    :comments_array_agg<item.index>,
                    :visibility_mode<item.index>,
                    parseDateTime64BestEffort(:created_at<item.index>, 9),
                    parseDateTime64BestEffort(:last_updated_at<item.index>, 9),
                    :created_by<item.index>,
                    :last_updated_by<item.index>
                )
                <if(item.hasNext)>,<endif>
            }>
            ;
            """;

    /**
     * Get experiment items count
     */
    private static final String GET_EXPERIMENT_ITEMS_COUNT = """
            SELECT count(DISTINCT id) as count
            FROM experiment_items FINAL
            WHERE workspace_id = :workspace_id
            AND experiment_id = :experiment_id
            SETTINGS log_comment = '<log_comment>'
            ;
            """;

    private static final String FIND_COUNT_FROM_AGGREGATES = """
            SELECT count(id) as count
            FROM experiment_aggregates FINAL
            <if(project_deleted)>
            LEFT JOIN (
                SELECT
                    experiment_id,
                    groupUniqArray(project_id) AS project_ids
                FROM experiment_items FINAL
                INNER JOIN traces FINAL ON experiment_items.trace_id = traces.id
                WHERE experiment_items.workspace_id = :workspace_id
                AND traces.workspace_id = :workspace_id
                <if(has_target_projects)>
                AND traces.project_id IN :target_project_ids
                <endif>
                GROUP BY experiment_id
            ) ep ON experiment_aggregates.id = ep.experiment_id
            <endif>
            WHERE workspace_id = :workspace_id
            <if(dataset_id)> AND dataset_id = :dataset_id <endif>
            <if(optimization_id)> AND optimization_id = :optimization_id <endif>
            <if(types)> AND type IN :types <endif>
            <if(name)> AND ilike(name, CONCAT('%', :name, '%')) <endif>
            <if(dataset_ids)> AND dataset_id IN :dataset_ids <endif>
            <if(experiment_ids)> AND id IN :experiment_ids <endif>
            <if(prompt_ids)> AND hasAny(mapKeys(prompt_versions), :prompt_ids) <endif>
            <if(filters)> AND <filters> <endif>
            <if(feedback_scores_aggregated_filters)> AND <feedback_scores_aggregated_filters> <endif>
            <if(feedback_scores_aggregated_empty_filters)> AND <feedback_scores_aggregated_empty_filters> <endif>
            <if(experiment_scores_filters)> AND <experiment_scores_filters> <endif>
            <if(experiment_scores_empty_filters)> AND <experiment_scores_empty_filters> <endif>
            <if(project_id)> AND project_id = :project_id <endif>
            <if(has_target_projects)> AND project_id IN :target_project_ids <endif>
            <if(project_deleted)> AND (has(ep.project_ids, '') OR empty(ep.project_ids)) <endif>
            SETTINGS log_comment = '<log_comment>'
            ;
            """;

    /**
     * Find experiment groups from experiment_aggregates table.
     * This query is optimized to use the aggregated project_id instead of joining with traces.
     */
    private static final String FIND_GROUPS_FROM_AGGREGATES = """
            SELECT <groupSelects>, max(created_at) AS last_created_experiment_at
            FROM experiment_aggregates FINAL
            WHERE workspace_id = :workspace_id
            <if(types)> AND type IN :types <endif>
            <if(name)> AND ilike(name, CONCAT('%', :name, '%')) <endif>
            <if(filters)> AND <filters> <endif>
            <if(project_id)> AND project_id = :project_id <endif>
            <if(project_deleted)> AND project_id = :zero_uuid <endif>
            GROUP BY <groupBy>
            SETTINGS log_comment = '<log_comment>'
            ;
            """;

    /**
     * Find experiment groups aggregations from experiment_aggregates table.
     * This query uses pre-aggregated metrics instead of calculating from raw tables.
     */
    private static final String FIND_GROUPS_AGGREGATIONS_FROM_AGGREGATES = """
            SELECT
                count(DISTINCT id) as experiment_count,
                sum(trace_count) as trace_count,
                sum(total_estimated_cost_sum) as total_estimated_cost,
                avg(total_estimated_cost_avg) as total_estimated_cost_avg,
                avgMap(feedback_scores_avg) as feedback_scores,
                avgMap(experiment_scores) as experiment_scores,
                avgMap(duration_percentiles) as duration,
                <groupSelects>
            FROM experiment_aggregates FINAL
            WHERE workspace_id = :workspace_id
            <if(types)> AND type IN :types <endif>
            <if(name)> AND ilike(name, CONCAT('%', :name, '%')) <endif>
            <if(filters)> AND <filters> <endif>
            <if(project_id)> AND project_id = :project_id <endif>
            <if(project_deleted)> AND project_id = :zero_uuid <endif>
            GROUP BY <groupBy>
            SETTINGS log_comment = '<log_comment>'
            ;
            """;

    private static final String SELECT_DATASET_ITEM_VERSIONS_WITH_EXPERIMENT_ITEMS_COUNT = """
            WITH dataset_item_versions_resolved AS (
                SELECT
                    div_dedup.id AS id,
                    div_dedup.dataset_item_id AS dataset_item_id,
                    div_dedup.dataset_id AS dataset_id,
                    div_dedup.data AS data,
                    div_dedup.source AS source,
                    div_dedup.trace_id AS trace_id,
                    div_dedup.span_id AS span_id,
                    div_dedup.tags AS tags,
                    div_dedup.evaluators AS evaluators,
                    div_dedup.execution_policy AS execution_policy,
                    div_dedup.created_at AS item_created_at,
                    div_dedup.last_updated_at AS item_last_updated_at,
                    div_dedup.created_by AS item_created_by,
                    div_dedup.last_updated_by AS item_last_updated_by,
                    div_dedup.dataset_version_id AS dataset_version_id
                FROM (
                    SELECT
                        div.id,
                        div.dataset_item_id,
                        div.dataset_id,
                        div.data,
                        div.source,
                        div.trace_id,
                        div.span_id,
                        div.tags,
                        div.evaluators,
                        div.execution_policy,
                        div.created_at,
                        div.last_updated_at,
                        div.created_by,
                        div.last_updated_by,
                        div.dataset_version_id,
                        div.workspace_id
                    FROM dataset_item_versions div
                    INNER JOIN experiment_aggregates ea FINAL ON
                        ea.workspace_id = div.workspace_id
                        AND ea.dataset_id = div.dataset_id
                        AND div.dataset_version_id = COALESCE(nullIf(ea.dataset_version_id, ''), :version_id)
                    WHERE div.workspace_id = :workspace_id
                    AND div.dataset_id = :dataset_id
                    <if(experiment_ids)>AND ea.id IN :experiment_ids<endif>
                    ORDER BY (div.workspace_id, div.dataset_id, div.dataset_version_id, div.id) DESC, div.last_updated_at DESC
                    LIMIT 1 BY div.id
                ) AS div_dedup
            )
            SELECT COUNT(DISTINCT eia.dataset_item_id) as count
            FROM experiment_item_aggregates eia FINAL
            INNER JOIN experiment_aggregates ea FINAL ON ea.id = eia.experiment_id
            LEFT JOIN dataset_item_versions_resolved AS di ON di.id = eia.dataset_item_id
            WHERE eia.workspace_id = :workspace_id
            <if(experiment_ids)>AND eia.experiment_id IN :experiment_ids<endif>
            <if(has_target_projects)>AND eia.project_id IN :target_project_ids<endif>
            <if(experiment_item_filters)>AND <experiment_item_filters><endif>
            <if(feedback_scores_filters)>AND <feedback_scores_filters><endif>
            <if(feedback_scores_empty_filters)>AND <feedback_scores_empty_filters><endif>
            <if(dataset_item_filters)>
            AND <dataset_item_filters>
            <endif>
            <if(search)>
            AND (
                multiSearchAnyCaseInsensitive(toString(eia.input), :searchTerms)
                OR multiSearchAnyCaseInsensitive(toString(eia.output), :searchTerms)
                OR multiSearchAnyCaseInsensitive(toString(COALESCE(di.data, map())), :searchTerms)
            )
            <endif>
            SETTINGS log_comment = '<log_comment>'
            ;
            """;

    private static final String SELECT_DATASET_ITEM_VERSIONS_WITH_EXPERIMENT_ITEMS = """
            WITH dataset_item_versions_resolved AS (
                SELECT
                    div_dedup.id AS id,
                    div_dedup.dataset_item_id AS dataset_item_id,
                    div_dedup.dataset_id AS dataset_id,
                    div_dedup.data AS data,
                    div_dedup.source AS source,
                    div_dedup.trace_id AS trace_id,
                    div_dedup.span_id AS span_id,
                    div_dedup.tags AS tags,
                    div_dedup.evaluators AS evaluators,
                    div_dedup.execution_policy AS execution_policy,
                    div_dedup.created_at AS item_created_at,
                    div_dedup.last_updated_at AS item_last_updated_at,
                    div_dedup.created_by AS item_created_by,
                    div_dedup.last_updated_by AS item_last_updated_by,
                    div_dedup.dataset_version_id AS dataset_version_id,
                    div_dedup.description AS description
                FROM (
                    SELECT
                        div.id,
                        div.dataset_item_id,
                        div.dataset_id,
                        div.data,
                        div.source,
                        div.trace_id,
                        div.span_id,
                        div.tags,
                        div.evaluators,
                        div.execution_policy,
                        div.created_at,
                        div.last_updated_at,
                        div.created_by,
                        div.last_updated_by,
                        div.dataset_version_id,
                        div.description,
                        div.workspace_id
                    FROM dataset_item_versions div
                    INNER JOIN experiment_aggregates ea FINAL ON
                        ea.workspace_id = div.workspace_id
                        AND ea.dataset_id = div.dataset_id
                        AND div.dataset_version_id = COALESCE(nullIf(ea.dataset_version_id, ''), :version_id)
                    WHERE div.workspace_id = :workspace_id
                    AND div.dataset_id = :dataset_id
                    <if(experiment_ids)>AND ea.id IN :experiment_ids<endif>
                    ORDER BY (div.workspace_id, div.dataset_id, div.dataset_version_id, div.id) DESC, div.last_updated_at DESC
                    LIMIT 1 BY div.id
                ) AS div_dedup
            )
            SELECT
                di.id AS id,
                di.dataset_item_id AS dataset_item_id,
                di.dataset_id AS dataset_id,
                di.data AS data,
                di.description AS description,
                di.source AS source,
                di.trace_id AS trace_id,
                di.span_id AS span_id,
                di.tags AS tags,
                di.evaluators AS evaluators,
                di.execution_policy AS execution_policy,
                di.item_created_at AS created_at,
                di.item_last_updated_at AS last_updated_at,
                di.item_created_by AS created_by,
                di.item_last_updated_by AS last_updated_by,
                groupArray((eia.id, eia.experiment_id, eia.dataset_item_id, eia.trace_id,
                           <if(truncate)> eia.input_slim <else> eia.input <endif>,
                           <if(truncate)> eia.output_slim <else> eia.output <endif>,
                           eia.feedback_scores_array,
                           eia.created_at, eia.last_updated_at, eia.created_by, eia.last_updated_by,
                           eia.comments_array_agg,
                           toFloat64(eia.duration),
                           eia.total_estimated_cost,
                           eia.usage,
                           eia.visibility_mode,
                           eia.metadata,
                           di.description
                )) AS experiment_items_array
            FROM experiment_item_aggregates eia FINAL
            INNER JOIN experiment_aggregates ea FINAL ON ea.id = eia.experiment_id
            LEFT JOIN dataset_item_versions_resolved AS di ON di.id = eia.dataset_item_id
            WHERE eia.workspace_id = :workspace_id
            <if(experiment_ids)>AND eia.experiment_id IN :experiment_ids<endif>
            <if(has_target_projects)>AND eia.project_id IN :target_project_ids<endif>
            <if(experiment_item_filters)>AND <experiment_item_filters><endif>
            <if(feedback_scores_filters)>AND <feedback_scores_filters><endif>
            <if(feedback_scores_empty_filters)>AND <feedback_scores_empty_filters><endif>
            <if(dataset_item_filters)>
            AND <dataset_item_filters>
            <endif>
            <if(search)>
            AND (
                multiSearchAnyCaseInsensitive(toString(eia.input), :searchTerms)
                OR multiSearchAnyCaseInsensitive(toString(eia.output), :searchTerms)
                OR multiSearchAnyCaseInsensitive(toString(COALESCE(di.data, map())), :searchTerms)
            )
            <endif>
            GROUP BY di.id, di.dataset_item_id, di.dataset_id, di.data, di.description, di.source, di.trace_id, di.span_id, di.tags,
                     di.evaluators, di.execution_policy, di.item_created_at, di.item_last_updated_at,
                     di.item_created_by, di.item_last_updated_by
            ORDER BY di.id DESC
            <if(limit)>LIMIT :limit<endif>
            <if(offset)>OFFSET :offset<endif>
            SETTINGS log_comment = '<log_comment>'
            ;
            """;

    private static final String SELECT_EXPERIMENT_ITEMS_STATS_FROM_AGGREGATES = """
            WITH valid_dataset_items AS (
                SELECT id
                FROM dataset_item_versions FINAL
                WHERE workspace_id = :workspace_id
                AND dataset_id = :dataset_id
                AND dataset_version_id = :version_id
                <if(dataset_item_filters)>
                AND (<dataset_item_filters>)
                <endif>
            ), feedback_scores_ungrouped AS (
                SELECT
                    workspace_id,
                    id,
                    experiment_id,
                    feedback_scores,
                    arrayMap(k -> (k, feedback_scores[k]), mapKeys(feedback_scores)) AS score_pairs
                FROM experiment_item_aggregates FINAL
                WHERE workspace_id = :workspace_id
                AND experiment_id IN :experiment_ids
                AND dataset_item_id IN (SELECT id FROM valid_dataset_items)
                <if(experiment_item_filters)>
                AND (<experiment_item_filters>)
                <endif>
            ), feedback_scores_flattened AS (
                SELECT
                    workspace_id,
                    id,
                    experiment_id,
                    tupleElement(score_pair, 1) AS name,
                    tupleElement(score_pair, 2) AS value
                FROM feedback_scores_ungrouped
                ARRAY JOIN score_pairs AS score_pair
            ), feedback_scores_percentiles AS (
                SELECT
                    name AS score_name,
                    quantiles(0.5, 0.9, 0.99)(toFloat64(value)) AS percentiles
                FROM feedback_scores_flattened
                GROUP BY name
            ), usage_total_tokens_data AS (
                SELECT
                    toFloat64(usage['total_tokens']) AS total_tokens
                FROM experiment_item_aggregates FINAL
                WHERE workspace_id = :workspace_id
                AND experiment_id IN :experiment_ids
                AND dataset_item_id IN (SELECT id FROM valid_dataset_items)
                <if(experiment_item_filters)>
                AND (<experiment_item_filters>)
                <endif>
                AND usage['total_tokens'] IS NOT NULL
                AND usage['total_tokens'] > 0
            )
            SELECT
                count(DISTINCT id) as experiment_items_count,
                count(DISTINCT trace_id) as trace_count,
                avgIf(total_estimated_cost, total_estimated_cost > 0) AS total_estimated_cost_,
                toDecimal128(if(isNaN(total_estimated_cost_), 0, total_estimated_cost_), 12) AS total_estimated_cost_avg,
                mapFromArrays(
                    ['p50', 'p90', 'p99'],
                    arrayMap(
                      v -> toDecimal128(
                             greatest(
                               least(if(isFinite(toFloat64(v)), toFloat64(v), 0), 999999999.999999999),
                               -999999999.999999999
                             ),
                             12
                           ),
                      quantilesIf(0.5, 0.9, 0.99)(total_estimated_cost, total_estimated_cost > 0)
                    )
                ) AS total_estimated_cost_percentiles,
                mapFromArrays(
                    ['p50', 'p90', 'p99'],
                    arrayMap(
                      v -> toDecimal64(
                             greatest(
                               least(if(isFinite(toFloat64(v)), toFloat64(v), 0), 999999999.999999999),
                               -999999999.999999999
                             ),
                             9
                           ),
                      quantiles(0.5, 0.9, 0.99)(duration)
                    )
                ) AS duration_percentiles,
                avgMap(feedback_scores) AS feedback_scores_avg,
                (SELECT mapFromArrays(
                    groupArray(score_name),
                    groupArray(mapFromArrays(
                        ['p50', 'p90', 'p99'],
                        arrayMap(v -> toDecimal64(if(isFinite(v), v, 0), 9), percentiles)
                    ))
                ) FROM feedback_scores_percentiles) AS feedback_scores_percentiles,
                avgMap(usage) AS usage,
                mapFromArrays(
                    ['p50', 'p90', 'p99'],
                    arrayMap(
                      v -> toInt64(greatest(least(if(isFinite(v), v, 0), 999999999.999999999), -999999999.999999999)),
                      (SELECT quantiles(0.5, 0.9, 0.99)(total_tokens) FROM usage_total_tokens_data)
                    )
                ) AS usage_total_tokens_percentiles
            FROM experiment_item_aggregates FINAL
            WHERE workspace_id = :workspace_id
            AND experiment_id IN :experiment_ids
            AND dataset_item_id IN (SELECT id FROM valid_dataset_items)
            <if(experiment_item_filters)>
            AND (<experiment_item_filters>)
            <endif>
            <if(feedback_scores_filters)>
            AND id IN (
                SELECT id
                FROM feedback_scores_flattened
                GROUP BY id, name
                HAVING (<feedback_scores_filters>)
            )
            <endif>
            <if(feedback_scores_empty_filters)>
            AND (<feedback_scores_empty_filters>)
            <endif>
            ;
            """;

    private static final String SELECT_EXPERIMENT_AGGREGATION_COUNTS = """
            SELECT
                count() AS total,
                countIf(has_aggregated) AS aggregated,
                countIf(NOT has_aggregated) AS not_aggregated
            FROM (
                SELECT
                    e.id,
                    notEmpty(agg.id) AS has_aggregated
                FROM experiments e FINAL
                LEFT JOIN (
                    SELECT DISTINCT
                        toString(id) AS id
                    FROM experiment_aggregates
                    WHERE workspace_id = :workspace_id
                    <if(experiment_ids)> AND id IN :experiment_ids <endif>
                    <if(dataset_id)> AND dataset_id = :dataset_id <endif>
                    <if(id)> AND id = :id <endif>
                    <if(ids_list)> AND id IN :ids_list <endif>
                ) agg ON e.id = agg.id
                WHERE e.workspace_id = :workspace_id
                <if(experiment_ids)> AND e.id IN :experiment_ids <endif>
                <if(dataset_id)> AND e.dataset_id = :dataset_id <endif>
                <if(id)> AND e.id = :id <endif>
                <if(ids_list)> AND e.id IN :ids_list <endif>
            )
            SETTINGS log_comment = '<log_comment>'
            ;
            """;

    @Override
    public Mono<Void> populateExperimentAggregate(UUID experimentId) {

        return getExperimentData(experimentId)
                .flatMap(experimentData -> {
                    // First check if experiment has any items
                    return getExperimentItemsCount(experimentId)
                            .flatMap(itemsCount -> {
                                if (itemsCount == 0) {
                                    return insertExperimentAggregate(
                                            experimentData,
                                            createEmptyTraceAggregations(experimentId),
                                            createEmptySpanAggregations(experimentId),
                                            createEmptyFeedbackScoreAggregations(experimentId),
                                            createEmptyPassRateAggregation(experimentId),
                                            "[]",
                                            0L);
                                }

                                return getProjectId(experimentId)
                                        .map(Optional::of)
                                        .defaultIfEmpty(Optional.empty())
                                        .flatMap(projectIdOpt -> {
                                            if (projectIdOpt.isEmpty()) {
                                                return insertExperimentAggregate(
                                                        experimentData,
                                                        createEmptyTraceAggregations(experimentId),
                                                        createEmptySpanAggregations(experimentId),
                                                        createEmptyFeedbackScoreAggregations(experimentId),
                                                        createEmptyPassRateAggregation(experimentId),
                                                        EMPTY_ARRAY_STR,
                                                        itemsCount);
                                            }

                                            var projectId = projectIdOpt.get();
                                            return Mono.zip(
                                                    getTraceAggregations(experimentId, projectId),
                                                    getSpanAggregations(experimentId, projectId),
                                                    getFeedbackScoreAggregations(experimentId, projectId),
                                                    getPassRateAggregation(experimentId, projectId)
                                                            .defaultIfEmpty(
                                                                    createEmptyPassRateAggregation(experimentId)),
                                                    getCommentsAggregation(experimentId, projectId)
                                                            .defaultIfEmpty(EMPTY_ARRAY_STR))
                                                    .flatMap(tuple -> {
                                                        var traceAgg = tuple.getT1();
                                                        var spanAgg = tuple.getT2();
                                                        var feedbackAgg = tuple.getT3();
                                                        var passRateAgg = tuple.getT4();
                                                        var commentsAgg = tuple.getT5();

                                                        return insertExperimentAggregate(
                                                                experimentData,
                                                                traceAgg,
                                                                spanAgg,
                                                                feedbackAgg,
                                                                passRateAgg,
                                                                commentsAgg,
                                                                itemsCount);
                                                    });
                                        });
                            });
                });
    }

    @Override
    public Mono<BatchResult> populateExperimentItemAggregates(UUID experimentId, UUID cursorId, int limit) {

        return Mono.deferContextual(ctx -> {
            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);

            return getExperimentItems(experimentId, cursorId, limit)
                    .collectList()
                    .flatMap(items -> {
                        if (items.isEmpty()) {
                            return Mono.just(new BatchResult(0L, null));
                        }

                        // Get the last cursor from the batch
                        var lastCursor = items.getLast().id();

                        // Get project_id first, then fetch data using subqueries (no trace IDs list)
                        return getProjectId(experimentId)
                                .flatMap(projectId -> Mono.zip(
                                        getTracesData(workspaceId, experimentId, projectId, cursorId, limit)
                                                .collectList(),
                                        getSpansData(workspaceId, experimentId, projectId, cursorId, limit)
                                                .collectList(),
                                        getFeedbackScoresData(workspaceId, experimentId, projectId, cursorId, limit)
                                                .collectList(),
                                        getCommentsData(workspaceId, experimentId, projectId, cursorId, limit)
                                                .collectList())
                                        .flatMap(tuple -> {
                                            var tracesData = tuple.getT1();
                                            var spansData = tuple.getT2();
                                            var feedbackData = tuple.getT3();
                                            var commentsData = tuple.getT4();

                                            return insertExperimentItemAggregates(
                                                    projectId,
                                                    items,
                                                    tracesData,
                                                    spansData,
                                                    feedbackData,
                                                    commentsData).map(count -> new BatchResult(count, lastCursor));
                                        }));
                    });
        });
    }

    private Mono<ExperimentData> getExperimentData(UUID experimentId) {
        return asyncTemplate.nonTransaction(connection -> makeFluxContextAware((userName, workspaceId) -> {
            var template = getSTWithLogComment(GET_EXPERIMENT_DATA,
                    "getExperimentData", workspaceId, experimentId.toString());

            var statement = connection.createStatement(template.render())
                    .bind("workspace_id", workspaceId)
                    .bind("experiment_id", experimentId);

            return Flux.from(statement.execute())
                    .flatMap(result -> result.map((row, metadata) -> mapExperimentData(row)));
        }).singleOrEmpty());
    }

    private Mono<UUID> getProjectId(UUID experimentId) {
        return asyncTemplate.nonTransaction(connection -> makeFluxContextAware((userName, workspaceId) -> {
            var template = getSTWithLogComment(GET_PROJECT_ID,
                    "getProjectId", workspaceId, experimentId.toString());

            var statement = connection.createStatement(template.render())
                    .bind("workspace_id", workspaceId)
                    .bind("experiment_id", experimentId);

            return Flux.from(statement.execute())
                    .flatMap(result -> result.map((row, metadata) -> UUID
                            .fromString(row.get("project_id", String.class))));
        }).singleOrEmpty());
    }

    private Mono<TraceAggregations> getTraceAggregations(UUID experimentId, UUID projectId) {
        return queryExperimentAggregation(
                GET_TRACE_AGGREGATIONS, "getTraceAggregations", experimentId, projectId,
                this::mapTraceAggregations);
    }

    private Mono<SpanAggregations> getSpanAggregations(UUID experimentId, UUID projectId) {
        return queryExperimentAggregation(
                GET_SPAN_AGGREGATIONS, "getSpanAggregations", experimentId, projectId,
                this::mapSpanAggregations);
    }

    private Mono<FeedbackScoreAggregations> getFeedbackScoreAggregations(UUID experimentId, UUID projectId) {
        return queryExperimentAggregation(
                GET_FEEDBACK_SCORE_AGGREGATIONS, "getFeedbackScoreAggregations", experimentId, projectId,
                this::mapFeedbackScoreAggregations);
    }

    private Mono<PassRateAggregation> getPassRateAggregation(UUID experimentId, UUID projectId) {
        return queryExperimentAggregation(
                GET_PASS_RATE_AGGREGATION, "getPassRateAggregation", experimentId, projectId,
                this::mapPassRateAggregation);
    }

    /**
     * Executes a single-row aggregation query scoped to a workspace, experiment, and project.
     * Handles the repeated context-aware execution, parameter binding, and singleOrEmpty pattern
     * shared by getTraceAggregations, getSpanAggregations, and getFeedbackScoreAggregations.
     */
    private <T> Mono<T> queryExperimentAggregation(
            String query,
            String logName,
            UUID experimentId,
            UUID projectId,
            Function<Row, T> rowMapper) {
        return asyncTemplate.nonTransaction(connection -> makeFluxContextAware((userName, workspaceId) -> {
            var template = getSTWithLogComment(query, logName, workspaceId, experimentId.toString());

            var statement = connection.createStatement(template.render())
                    .bind("workspace_id", workspaceId)
                    .bind("experiment_id", experimentId)
                    .bind("project_id", projectId);

            return Flux.from(statement.execute())
                    .flatMap(result -> result.map((row, metadata) -> rowMapper.apply(row)));
        }).singleOrEmpty());
    }

    private Mono<Long> getExperimentItemsCount(UUID experimentId) {
        return asyncTemplate.nonTransaction(connection -> makeFluxContextAware((userName, workspaceId) -> {
            var template = getSTWithLogComment(GET_EXPERIMENT_ITEMS_COUNT,
                    "getExperimentItemsCount", workspaceId, experimentId.toString());

            var statement = connection.createStatement(template.render())
                    .bind("workspace_id", workspaceId)
                    .bind("experiment_id", experimentId);

            return Flux.from(statement.execute())
                    .flatMap(result -> result.map((row, metadata) -> row.get("count", Long.class)));
        }).singleOrEmpty()
                .defaultIfEmpty(0L));
    }

    private Mono<Void> insertExperimentAggregate(
            ExperimentData experimentData,
            TraceAggregations traceAgg,
            SpanAggregations spanAgg,
            FeedbackScoreAggregations feedbackAgg,
            PassRateAggregation passRateAgg,
            String commentsArrayAgg,
            long itemsCount) {

        return asyncTemplate.nonTransaction(connection -> {
            var template = getSTWithLogComment(INSERT_EXPERIMENT_AGGREGATE,
                    "insertExperimentAggregate", experimentData.workspaceId(), experimentData.id().toString());

            // Convert Maps to key/value arrays for ClickHouse mapFromArrays
            var experimentScoresArrays = mapToArrays(
                    ObjectUtils.getIfNull(experimentData.experimentScores(), Map.of()),
                    String[]::new, Double[]::new,
                    v -> v.doubleValue());
            var durationPercentilesArrays = mapToArrays(
                    ObjectUtils.getIfNull(traceAgg.durationPercentiles(), Map.of()),
                    String[]::new, Double[]::new,
                    v -> v);
            var totalEstimatedCostPercentilesArrays = mapToArrays(
                    ObjectUtils.getIfNull(spanAgg.totalEstimatedCostPercentiles(), Map.of()),
                    String[]::new, Double[]::new,
                    v -> v);
            var usageAvgArrays = mapToArrays(
                    ObjectUtils.getIfNull(spanAgg.usageAvg(), Map.of()),
                    String[]::new, Double[]::new,
                    Double::doubleValue);
            Map<String, Double> usageTotalTokensPercentiles = ObjectUtils.getIfNull(
                    spanAgg.usageTotalTokensPercentiles(),
                    Map.of());
            var usageTotalTokensPercentilesArrays = mapToArrays(
                    usageTotalTokensPercentiles,
                    String[]::new, Double[]::new,
                    v -> v);
            var feedbackScoresAvgArrays = mapToArrays(
                    ObjectUtils.getIfNull(feedbackAgg.feedbackScoresAvg(), Map.of()),
                    String[]::new, Double[]::new,
                    Double::doubleValue);

            var statement = connection.createStatement(template.render())
                    .bind("workspace_id", experimentData.workspaceId())
                    .bind("id", experimentData.id())
                    .bind("dataset_id", experimentData.datasetId())
                    .bind("project_id", traceAgg.projectId())
                    .bind("name", experimentData.name())
                    .bind("created_at", experimentData.createdAt())
                    .bind("last_updated_at", experimentData.lastUpdatedAt())
                    .bind("created_by", experimentData.createdBy())
                    .bind("last_updated_by", experimentData.lastUpdatedBy())
                    .bind("metadata", ObjectUtils.getIfNull(experimentData.metadata(), ""))
                    .bind("prompt_versions", ObjectUtils.getIfNull(experimentData.promptVersions(), Map.of()))
                    .bind("optimization_id", ObjectUtils.getIfNull(experimentData.optimizationId(), ""))
                    .bind("dataset_version_id", ObjectUtils.getIfNull(experimentData.datasetVersionId(), ""))
                    .bind("tags", ObjectUtils.getIfNull(experimentData.tags(), List.of()).toArray(new String[0]))
                    .bind("type", experimentData.type())
                    .bind("evaluation_method",
                            ObjectUtils.getIfNull(experimentData.evaluationMethod(),
                                    EvaluationMethod.UNKNOWN_VALUE))
                    .bind("status", experimentData.status())
                    .bind("experiment_scores_keys", experimentScoresArrays.keys())
                    .bind("experiment_scores_values", experimentScoresArrays.values())
                    .bind("trace_count", traceAgg.traceCount())
                    .bind("experiment_items_count", itemsCount)
                    .bind("duration_percentiles_keys", durationPercentilesArrays.keys())
                    .bind("duration_percentiles_values", durationPercentilesArrays.values())
                    .bind("feedback_scores_percentiles",
                            ObjectUtils.getIfNull(feedbackAgg.feedbackScoresPercentiles(), Map.of()))
                    .bind("total_estimated_cost_sum", spanAgg.totalEstimatedCostSum())
                    .bind("total_estimated_cost_avg", spanAgg.totalEstimatedCostAvg())
                    .bind("total_estimated_cost_percentiles_keys", totalEstimatedCostPercentilesArrays.keys())
                    .bind("total_estimated_cost_percentiles_values", totalEstimatedCostPercentilesArrays.values())
                    .bind("usage_avg_keys", usageAvgArrays.keys())
                    .bind("usage_avg_values", usageAvgArrays.values())
                    .bind("usage_total_tokens_percentiles_keys", usageTotalTokensPercentilesArrays.keys())
                    .bind("usage_total_tokens_percentiles_values", usageTotalTokensPercentilesArrays.values())
                    .bind("feedback_scores_avg_keys", feedbackScoresAvgArrays.keys())
                    .bind("feedback_scores_avg_values", feedbackScoresAvgArrays.values())
                    .bind("pass_rate", passRateAgg.passRate())
                    .bind("passed_count", passRateAgg.passedCount())
                    .bind("total_count", passRateAgg.totalCount())
                    .bind("comments_array_agg",
                            StringUtils.isNotBlank(commentsArrayAgg) ? commentsArrayAgg : EMPTY_ARRAY_STR);

            return makeMonoContextAware((userName, workspaceId) -> Mono.from(statement.execute()).then());
        });
    }

    private Flux<ExperimentItemData> getExperimentItems(UUID experimentId,
            UUID cursor, int limit) {
        return asyncTemplate.stream(connection -> makeFluxContextAware((userName, workspaceId) -> {
            var template = getSTWithLogComment(GET_EXPERIMENT_ITEMS,
                    "getExperimentItems", workspaceId, experimentId.toString())
                    .add("cursor", cursor != null);

            var statement = connection.createStatement(template.render())
                    .bind("workspace_id", workspaceId)
                    .bind("experiment_id", experimentId)
                    .bind("limit", limit);

            if (cursor != null) {
                statement.bind("cursor", cursor);
            }

            return Flux.from(statement.execute())
                    .flatMap(result -> result.map((row, metadata) -> mapExperimentItemData(row)));
        }));
    }

    /**
     * Helper method to execute paginated queries with cursor-based pagination.
     * Eliminates boilerplate for binding workspace_id, experiment_id, project_id, limit, and optional cursor.
     *
     * @param sqlTemplate   SQL template constant
     * @param methodName    Method name for log comment
     * @param workspaceId   Workspace ID
     * @param experimentId  Experiment ID
     * @param projectId     Project ID
     * @param cursor        Optional cursor for pagination
     * @param limit         Page size limit
     * @param rowMapper     Function to map result row to target type
     * @param <T>           Return type
     * @return Flux of mapped results
     */
    private <T> Flux<T> streamWithExperimentPagination(
            String sqlTemplate,
            String methodName,
            String workspaceId,
            UUID experimentId,
            UUID projectId,
            UUID cursor,
            int limit,
            Function<Row, T> rowMapper) {

        return asyncTemplate.stream(connection -> {
            var template = getSTWithLogComment(sqlTemplate, methodName, workspaceId, experimentId.toString())
                    .add("cursor", cursor != null);

            var statement = connection.createStatement(template.render())
                    .bind("workspace_id", workspaceId)
                    .bind("experiment_id", experimentId)
                    .bind("project_id", projectId)
                    .bind("limit", limit);

            if (cursor != null) {
                statement.bind("cursor", cursor);
            }

            return Flux.from(statement.execute())
                    .flatMap(result -> result.map((row, metadata) -> rowMapper.apply(row)));
        });
    }

    private Flux<TraceData> getTracesData(String workspaceId, UUID experimentId, UUID projectId,
            UUID cursor, int limit) {
        return streamWithExperimentPagination(
                GET_TRACES_DATA,
                "getTracesData",
                workspaceId,
                experimentId,
                projectId,
                cursor,
                limit,
                this::mapTraceData);
    }

    private Flux<SpanData> getSpansData(String workspaceId, UUID experimentId, UUID projectId,
            UUID cursor, int limit) {
        return streamWithExperimentPagination(
                GET_SPANS_DATA,
                "getSpansData",
                workspaceId,
                experimentId,
                projectId,
                cursor,
                limit,
                this::mapSpanData);
    }

    private Flux<FeedbackScoreData> getFeedbackScoresData(String workspaceId, UUID experimentId, UUID projectId,
            UUID cursor, int limit) {
        return streamWithExperimentPagination(
                GET_FEEDBACK_SCORES_DATA,
                "getFeedbackScoresData",
                workspaceId,
                experimentId,
                projectId,
                cursor,
                limit,
                this::mapFeedbackScoreData);
    }

    private Flux<CommentsData> getCommentsData(String workspaceId, UUID experimentId, UUID projectId,
            UUID cursor, int limit) {
        return streamWithExperimentPagination(
                GET_COMMENTS_DATA,
                "getCommentsData",
                workspaceId,
                experimentId,
                projectId,
                cursor,
                limit,
                this::mapCommentsData);
    }

    private Mono<String> getCommentsAggregation(UUID experimentId, UUID projectId) {
        return queryExperimentAggregation(
                GET_COMMENTS_AGGREGATION, "getCommentsAggregation", experimentId, projectId,
                row -> {
                    var value = row.get("comments_array_agg", String.class);
                    return StringUtils.isNotBlank(value) ? value : EMPTY_ARRAY_STR;
                });
    }

    private void bindItemsParameters(Statement statement,
            List<ExperimentItemData> items,
            Map<UUID, TraceData> tracesMap,
            Map<UUID, SpanData> spansMap,
            Map<UUID, FeedbackScoreData> feedbackMap,
            Map<UUID, CommentsData> commentsMap) {

        for (int i = 0; i < items.size(); i++) {
            var item = items.get(i);

            TraceData trace = tracesMap.get(item.traceId());
            SpanData span = spansMap.get(item.traceId());
            FeedbackScoreData feedback = feedbackMap.get(item.traceId());

            Map<String, Long> usageMap = Optional.ofNullable(span)
                    .map(SpanData::usage)
                    .orElse(Map.of());
            Map<String, BigDecimal> feedbackScoresMap = Optional.ofNullable(feedback)
                    .map(FeedbackScoreData::feedbackScores)
                    .orElse(Map.of());
            String feedbackScoresArray = Optional.ofNullable(feedback)
                    .map(FeedbackScoreData::feedbackScoresArray)
                    .orElse(EMPTY_ARRAY_STR);

            statement.bind("id" + i, item.id())
                    .bind("has_usage" + i, !usageMap.isEmpty())
                    .bind("has_feedback_scores" + i, !feedbackScoresMap.isEmpty())
                    .bind("experiment_id" + i, item.experimentId())
                    .bind("dataset_item_id" + i, item.datasetItemId())
                    .bind("trace_id" + i, item.traceId())
                    .bind("input" + i, Optional.ofNullable(trace).map(TraceData::input).orElse(""))
                    .bind("output" + i, Optional.ofNullable(trace).map(TraceData::output).orElse(""))
                    .bind("input_slim" + i, Optional.ofNullable(trace).map(TraceData::inputSlim).orElse(""))
                    .bind("output_slim" + i, Optional.ofNullable(trace).map(TraceData::outputSlim).orElse(""))
                    .bind("metadata" + i, Optional.ofNullable(trace).map(TraceData::metadata).orElse(""))
                    .bind("duration" + i, Optional.ofNullable(trace).map(TraceData::duration).orElse(BigDecimal.ZERO))
                    .bind("total_estimated_cost" + i,
                            Optional.ofNullable(span).map(SpanData::totalEstimatedCost).orElse(BigDecimal.ZERO))
                    .bind("feedback_scores_array" + i, feedbackScoresArray)
                    .bind("comments_array_agg" + i,
                            Optional.ofNullable(commentsMap.get(item.traceId()))
                                    .map(CommentsData::commentsArrayAgg)
                                    .orElse(EMPTY_ARRAY_STR))
                    .bind("visibility_mode" + i,
                            Optional.ofNullable(trace)
                                    .map(TraceData::visibilityMode)
                                    .map(VisibilityMode::getValue)
                                    .orElse(VisibilityMode.DEFAULT.getValue()))
                    .bind("created_at" + i, item.createdAt().toString())
                    .bind("last_updated_at" + i, item.lastUpdatedAt().toString())
                    .bind("created_by" + i, item.createdBy())
                    .bind("last_updated_by" + i, item.lastUpdatedBy());

            // Bind array parameters only if maps are not empty
            var usageArrays = mapToArrays(usageMap, String[]::new, Long[]::new, Long::longValue);
            statement.bind("usage_keys" + i, usageArrays.keys());
            statement.bind("usage_values" + i, usageArrays.values());

            var feedbackScoresArrays = mapToArrays(feedbackScoresMap,
                    String[]::new, BigDecimal[]::new,
                    v -> BigDecimal.valueOf(v.doubleValue()));
            statement.bind("feedback_scores_keys" + i, feedbackScoresArrays.keys());
            statement.bind("feedback_scores_values" + i, feedbackScoresArrays.values());
        }
    }

    private Mono<Long> insertExperimentItemAggregates(
            UUID projectId,
            List<ExperimentItemData> items,
            List<TraceData> tracesData,
            List<SpanData> spansData,
            List<FeedbackScoreData> feedbackData,
            List<CommentsData> commentsData) {

        // Create lookup maps
        Map<UUID, TraceData> tracesMap = tracesData.stream()
                .collect(Collectors.toMap(TraceData::traceId, t -> t));
        Map<UUID, SpanData> spansMap = spansData.stream()
                .collect(Collectors.toMap(SpanData::traceId, s -> s));
        Map<UUID, FeedbackScoreData> feedbackMap = feedbackData.stream()
                .collect(Collectors.toMap(FeedbackScoreData::traceId, f -> f));
        Map<UUID, CommentsData> commentsMap = commentsData.stream()
                .collect(Collectors.toMap(CommentsData::traceId, c -> c));

        return asyncTemplate.nonTransaction(connection -> {
            return makeMonoContextAware((userName, workspaceId) -> {

                List<TemplateUtils.QueryItem> queryItems = getQueryItemPlaceHolder(items.size());

                var template = getSTWithLogComment(INSERT_EXPERIMENT_ITEM_AGGREGATE,
                        "insertExperimentItemAggregate", workspaceId, items.size())
                        .add("items", queryItems);

                var statement = connection.createStatement(template.render())
                        .bind("workspace_id", workspaceId)
                        .bind("project_id", projectId);

                // Bind item parameters in batch
                bindItemsParameters(statement, items, tracesMap, spansMap, feedbackMap, commentsMap);

                return Mono.from(statement.execute())
                        .flatMapMany(Result::getRowsUpdated)
                        .reduce(0L, Long::sum);
            });
        });
    }

    // Row mapping methods
    private ExperimentData mapExperimentData(Row row) {
        return ExperimentData.builder()
                .workspaceId(row.get("workspace_id", String.class))
                .id(getUUID(row, "id"))
                .datasetId(getUUID(row, "dataset_id"))
                .name(row.get("name", String.class))
                .createdAt(row.get("created_at", String.class))
                .lastUpdatedAt(row.get("last_updated_at", String.class))
                .createdBy(row.get("created_by", String.class))
                .lastUpdatedBy(row.get("last_updated_by", String.class))
                .metadata(row.get("metadata", String.class))
                .promptVersions(row.get("prompt_versions", Map.class))
                .optimizationId(row.get("optimization_id", String.class))
                .datasetVersionId(row.get("dataset_version_id", String.class))
                .tags(Optional.ofNullable(row.get("tags", String[].class))
                        .map(tags -> Arrays.stream(tags).toList())
                        .filter(CollectionUtils::isNotEmpty)
                        .orElse(null))
                .type(row.get("type", String.class))
                .evaluationMethod(row.get("evaluation_method", String.class))
                .status(row.get("status", String.class))
                .experimentScores(parseExperimentScoresFromString(row.get("experiment_scores", String.class)))
                .build();
    }

    /**
     * Parse experiment_scores from JSON string (experiments table stores as String).
     * Returns empty map if input is null/empty or parsing fails.
     */
    private Map<String, BigDecimal> parseExperimentScoresFromString(String experimentScores) {
        if (StringUtils.isBlank(experimentScores)) {
            return Map.of();
        }

        return JsonUtils.readValue(experimentScores, TYPE_REFERENCE)
                .stream()
                .collect(Collectors.toMap(ExperimentScore::name, ExperimentScore::value));
    }

    private TraceAggregations mapTraceAggregations(Row row) {
        return TraceAggregations.builder()
                .experimentId(getUUID(row, "experiment_id"))
                .projectId(getUUID(row, "project_id"))
                .durationPercentiles(mapNumberMap(row, "duration_percentiles"))
                .traceCount(row.get("trace_count", Long.class))
                .build();
    }

    private static Map<String, Double> mapNumberMap(Row row, String columnName) {
        return Optional.ofNullable((Map<String, ? extends Number>) row.get(columnName, Map.class))
                .orElse(Map.of())
                .entrySet()
                .stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().doubleValue()));

    }

    private SpanAggregations mapSpanAggregations(Row row) {
        return SpanAggregations.builder()
                .experimentId(getUUID(row, "experiment_id"))
                .usageAvg(row.get("usage_avg", Map.class))
                .totalEstimatedCostSum(row.get("total_estimated_cost_sum", Double.class))
                .totalEstimatedCostAvg(row.get("total_estimated_cost_avg", Double.class))
                .totalEstimatedCostPercentiles(mapNumberMap(row, "total_estimated_cost_percentiles"))
                .usageTotalTokensPercentiles(mapNumberMap(row, "usage_total_tokens_percentiles"))
                .build();
    }

    private FeedbackScoreAggregations mapFeedbackScoreAggregations(Row row) {
        // Convert feedbackScoresAvg map values from BigDecimal to Double
        Map<String, Object> feedbackScoresAvgRaw = row.get("feedback_scores_avg", Map.class);
        Map<String, Double> feedbackScoresAvg = Optional.ofNullable(feedbackScoresAvgRaw)
                .map(raw -> raw.entrySet().stream()
                        .collect(Collectors.toMap(
                                Map.Entry::getKey,
                                e -> ((Number) e.getValue()).doubleValue())))
                .orElse(null);

        return FeedbackScoreAggregations.builder()
                .experimentId(getUUID(row, "experiment_id"))
                .feedbackScoresPercentiles(row.get("feedback_scores_percentiles", Map.class))
                .feedbackScoresAvg(feedbackScoresAvg)
                .build();
    }

    private PassRateAggregation mapPassRateAggregation(Row row) {
        return PassRateAggregation.builder()
                .experimentId(getUUID(row, "experiment_id"))
                .passRate(row.get("pass_rate", BigDecimal.class))
                .passedCount(row.get("passed_count", Long.class))
                .totalCount(row.get("total_count", Long.class))
                .build();
    }

    private ExperimentItemData mapExperimentItemData(Row row) {
        return ExperimentItemData.builder()
                .id(getUUID(row, "id"))
                .experimentId(getUUID(row, "experiment_id"))
                .traceId(getUUID(row, "trace_id"))
                .datasetItemId(getUUID(row, "dataset_item_id"))
                .createdAt(row.get("created_at", Instant.class))
                .lastUpdatedAt(row.get("last_updated_at", Instant.class))
                .createdBy(row.get("created_by", String.class))
                .lastUpdatedBy(row.get("last_updated_by", String.class))
                .build();
    }

    /**
     * Query experiment_aggregates table directly and construct Experiment from stored aggregated values.
     * Used for testing and verification that aggregated data matches expected values.
     *
     * @param experimentId the experiment ID to query
     * @return Mono containing the Experiment constructed from aggregates table, or empty if not found
     */
    public Mono<Experiment> getExperimentFromAggregates(@NonNull UUID experimentId) {

        return Mono.deferContextual(context -> {
            String workspaceId = context.get(RequestContext.WORKSPACE_ID);

            return asyncTemplate.nonTransaction(connection -> {

                var template = getSTWithLogComment(SELECT_EXPERIMENT_BY_ID,
                        "getExperimentFromAggregates", workspaceId, experimentId.toString());

                var statement = connection.createStatement(template.render())
                        .bind("workspace_id", workspaceId)
                        .bind("experiment_id", experimentId.toString());

                return Flux.from(statement.execute())
                        .flatMap(result -> result.map((row, rowMetadata) -> mapExperimentFromAggregates(row)))
                        .singleOrEmpty();
            });
        });
    }

    /**
     * Maps a row from experiment_aggregates table to an Experiment object.
     */
    private Experiment mapExperimentFromAggregates(Row row) {
        // id is FixedString(36) in ClickHouse, read as String
        UUID id = getUUID(row, "id");

        // dataset_id is FixedString(36), read as String
        UUID datasetId = getUUID(row, "dataset_id");

        // project_id is FixedString(36), read as String
        UUID projectId = getUUID(row, "project_id");

        // name is String
        String name = row.get("name", String.class);

        // created_at is DateTime64(9, 'UTC'), read as Instant
        Instant createdAt = row.get("created_at", Instant.class);

        // last_updated_at is DateTime64(9, 'UTC'), read as Instant
        Instant lastUpdatedAt = row.get("last_updated_at", Instant.class);

        // created_by is String
        String createdBy = row.get("created_by", String.class);

        // last_updated_by is String
        String lastUpdatedBy = row.get("last_updated_by", String.class);

        // metadata is String (JSON), parse to JsonNode
        JsonNode metadata = getJsonNodeOrNull(row, "metadata");

        // tags is Array(String), read as List<String> and convert to Set<String>
        Set<String> tags = Set.copyOf(Optional.ofNullable(row.get("tags", List.class)).orElse(List.of()));

        // type is Enum8, read as String and convert to ExperimentType
        ExperimentType type = ExperimentType.fromString(row.get("type", String.class));

        // evaluation_method is Enum, read as String and convert to EvaluationMethod
        EvaluationMethod evaluationMethod = EvaluationMethod.fromString(row.get("evaluation_method", String.class))
                .orElse(null);

        // status is Enum8, read as String and convert to ExperimentStatus
        ExperimentStatus status = ExperimentStatus.fromString(row.get("status", String.class));

        // optimization_id is String, convert to UUID
        UUID optimizationId = getUUIDOrNull(row, "optimization_id");

        // dataset_version_id is String, convert to UUID
        UUID datasetVersionId = getUUIDOrNull(row, "dataset_version_id");

        // prompt_versions - ignored in comparison, set to null
        // Database has Map(FixedString(36), Array(FixedString(36)))
        // Java expects List<PromptVersionLink> - complex conversion not needed since ignored
        List<Experiment.PromptVersionLink> promptVersions = null;

        // experiment_scores is Map(String, Float64), convert to List<ExperimentScore>
        Map<String, Double> experimentScoresRaw = row.get("experiment_scores", Map.class);

        List<ExperimentScore> experimentScores = Optional.ofNullable(experimentScoresRaw)
                .map(raw -> raw.entrySet().stream()
                        .map(e -> new ExperimentScore(e.getKey(), BigDecimal.valueOf(e.getValue())))
                        .toList())
                .orElse(null);

        // trace_count is UInt64 in ClickHouse, read as Long
        Long traceCount = row.get("trace_count", Long.class);

        // duration_percentiles is Map(String, Float64), read as Map<String, Double>
        Map<String, Double> durationMap = row.get("duration_percentiles", Map.class);

        PercentageValues duration = Optional.ofNullable(durationMap)
                .filter(map -> !map.isEmpty())
                .map(map -> new PercentageValues(
                        BigDecimal.valueOf(map.getOrDefault("p50", 0.0)),
                        BigDecimal.valueOf(map.getOrDefault("p90", 0.0)),
                        BigDecimal.valueOf(map.getOrDefault("p99", 0.0))))
                .orElse(null);

        // feedback_scores_avg is Map(String, Float64), convert to List<FeedbackScoreAverage>
        Map<String, Double> feedbackScoresAvgRaw = row.get("feedback_scores_avg", Map.class);
        List<FeedbackScoreAverage> feedbackScores = Optional.ofNullable(feedbackScoresAvgRaw)
                .map(raw -> raw.entrySet().stream()
                        .map(e -> new FeedbackScoreAverage(e.getKey(),
                                BigDecimal.valueOf(e.getValue())))
                        .toList())
                .orElse(null);

        // total_estimated_cost_sum is Float64, read as Double
        BigDecimal totalEstimatedCost = getBigDecimal(row, "total_estimated_cost_sum");

        // total_estimated_cost_avg is Float64, read as Double
        BigDecimal totalEstimatedCostAvg = getBigDecimal(row, "total_estimated_cost_avg");

        // usage_avg is Map(String, Float64), read as Map<String, Double>
        Map<String, Double> usageAvg = row.get("usage_avg", Map.class);

        // pass_rate fields are non-nullable in experiment_aggregates (DEFAULT 0)
        // Convert 0 defaults to null for non-evaluation-suite experiments
        Long totalCount = row.get("total_count", Long.class);
        boolean hasPassRate = totalCount != null && totalCount > 0;

        // Build Experiment with all fields from experiment_aggregates table
        return new Experiment(
                id,
                null, // datasetName - not in DB
                datasetId,
                projectId,
                null, // projectName - not in DB
                name,
                metadata,
                tags,
                type,
                evaluationMethod,
                optimizationId,
                feedbackScores,
                CommentResultMapper.parseCommentsFromJson(row.get("comments_array_agg", String.class)),
                traceCount,
                createdAt,
                duration,
                totalEstimatedCost, // total_estimated_cost_sum
                totalEstimatedCostAvg,
                usageAvg,
                lastUpdatedAt,
                createdBy,
                lastUpdatedBy,
                status,
                experimentScores,
                null, // promptVersion (singular) - not in DB
                promptVersions,
                datasetVersionId,
                null, // datasetVersionSummary - not in DB
                hasPassRate ? row.get("pass_rate", BigDecimal.class) : null,
                hasPassRate ? row.get("passed_count", Long.class) : null,
                hasPassRate ? totalCount : null);
    }

    /**
     * Helper to read UUID from ClickHouse FixedString(36) column.
     */
    private UUID getUUID(Row row, String columnName) {
        return UUID.fromString(row.get(columnName, String.class));
    }

    /**
     * Helper to read UUID from ClickHouse FixedString(36) column with null handling.
     */
    private UUID getUUIDOrNull(Row row, String columnName) {
        String value = row.get(columnName, String.class);
        return StringUtils.isNotBlank(value) ? UUID.fromString(value) : null;
    }

    private BigDecimal getBigDecimal(Row row, String columnName) {
        return row.get(columnName, BigDecimal.class);
    }

    /**
     * Helper to parse JsonNode from String with null handling.
     */
    private JsonNode getJsonNodeOrNull(Row row, String columnName) {
        String value = row.get(columnName, String.class);
        return StringUtils.isNotBlank(value) ? JsonUtils.getJsonNodeFromString(value) : null;
    }

    private <K, V, R> MapArrays<K, R> mapToArrays(Map<K, V> map, IntFunction<K[]> keysGenerator,
            IntFunction<R[]> valuesGenerator,
            Function<V, R> valueConverter) {
        if (map == null || map.isEmpty()) {
            return new MapArrays<>(keysGenerator.apply(0), valuesGenerator.apply(0));
        }

        var keys = map.keySet().toArray(keysGenerator.apply(map.size()));
        var values = valuesGenerator.apply(keys.length);
        for (int i = 0; i < keys.length; i++) {
            values[i] = valueConverter.apply(map.get(keys[i]));
        }
        return new MapArrays<>(keys, values);
    }

    private TraceData mapTraceData(Row row) {
        // visibility_mode is Enum8, read as String and convert to VisibilityMode
        VisibilityMode visibilityMode = VisibilityMode.fromString(row.get("visibility_mode", String.class))
                .orElse(VisibilityMode.DEFAULT);

        return TraceData.builder()
                .traceId(getUUID(row, "trace_id"))
                .projectId(getUUID(row, "project_id"))
                .duration(row.get("duration", BigDecimal.class))
                .metadata(row.get("metadata", String.class))
                .input(row.get("input", String.class))
                .output(row.get("output", String.class))
                .inputSlim(row.get("input_slim", String.class))
                .outputSlim(row.get("output_slim", String.class))
                .visibilityMode(visibilityMode)
                .build();
    }

    private ExperimentSourceData.SpanData mapSpanData(Row row) {
        return ExperimentSourceData.SpanData.builder()
                .traceId(getUUID(row, "trace_id"))
                .usage(row.get("usage", Map.class))
                .totalEstimatedCost(row.get("total_estimated_cost", BigDecimal.class))
                .build();
    }

    private FeedbackScoreData mapFeedbackScoreData(Row row) {
        List[] feedbackScoresArray = row.get("feedback_scores_array", List[].class);

        // Map to FeedbackScore objects before serializing to JSON
        List<FeedbackScore> feedbackScores = FeedbackScoreMapper.getFeedbackScores(feedbackScoresArray);
        String feedbackScoresArrayJson = Optional.ofNullable(feedbackScores)
                .map(JsonUtils::writeValueAsString)
                .orElse(EMPTY_ARRAY_STR);

        return FeedbackScoreData.builder()
                .traceId(getUUID(row, "trace_id"))
                .feedbackScores(row.get("feedback_scores", Map.class))
                .feedbackScoresArray(feedbackScoresArrayJson)
                .build();
    }

    private CommentsData mapCommentsData(Row row) {
        var commentsArrayAgg = row.get("comments_array_agg", String.class);
        return CommentsData.builder()
                .traceId(getUUID(row, "trace_id"))
                .commentsArrayAgg(StringUtils.isNotBlank(commentsArrayAgg) ? commentsArrayAgg : EMPTY_ARRAY_STR)
                .build();
    }

    @Override
    public Mono<Long> countTotal(ExperimentSearchCriteria experimentSearchCriteria) {
        return asyncTemplate.nonTransaction(connection -> countTotalFromAggregates(experimentSearchCriteria, connection)
                .flatMap(result -> Flux.from(result.map((row, rowMetadata) -> row.get("count", Long.class))))
                .reduce(0L, Long::sum));
    }

    private Flux<? extends Result> countTotalFromAggregates(
            ExperimentSearchCriteria experimentSearchCriteria, Connection connection) {

        return makeFluxContextAware((userName, workspaceId) -> {
            var template = buildCountTemplate(experimentSearchCriteria, workspaceId);

            var statement = connection.createStatement(template.render())
                    .bind("workspace_id", workspaceId);

            bindSearchCriteria(statement, experimentSearchCriteria);
            return Flux.from(statement.execute());
        });
    }

    private ST buildCountTemplate(ExperimentSearchCriteria criteria, String workspaceId) {
        var template = getSTWithLogComment(FIND_COUNT_FROM_AGGREGATES, "count_experiments_from_aggregates",
                workspaceId, "");
        Optional.ofNullable(criteria.datasetId())
                .ifPresent(datasetId -> template.add("dataset_id", datasetId));
        Optional.ofNullable(criteria.name())
                .ifPresent(name -> template.add("name", name));
        Optional.ofNullable(criteria.datasetIds())
                .ifPresent(datasetIds -> template.add("dataset_ids", datasetIds));
        Optional.ofNullable(criteria.promptId())
                .ifPresent(promptId -> template.add("prompt_ids", promptId));
        Optional.ofNullable(criteria.projectId())
                .ifPresent(projectId -> template.add("project_id", projectId));
        if (criteria.projectDeleted()) {
            template.add("project_deleted", true);
        }
        Optional.ofNullable(criteria.optimizationId())
                .ifPresent(optimizationId -> template.add("optimization_id", optimizationId));
        Optional.ofNullable(criteria.types())
                .filter(types -> types != null && !types.isEmpty())
                .ifPresent(types -> template.add("types", types));
        Optional.ofNullable(criteria.experimentIds())
                .filter(experimentIds -> experimentIds != null && !experimentIds.isEmpty())
                .ifPresent(experimentIds -> template.add("experiment_ids", experimentIds));

        // Add regular experiment filters
        Optional.ofNullable(criteria.filters())
                .flatMap(filters -> filterQueryBuilder.toAnalyticsDbFilters(filters, FilterStrategy.EXPERIMENT))
                .ifPresent(experimentFilters -> template.add("filters", experimentFilters));

        // Add aggregated feedback score filters (ONLY REFERENCED HERE in ExperimentAggregatesDAO)
        Optional.ofNullable(criteria.filters())
                .flatMap(filters -> filterQueryBuilder.toAnalyticsDbFilters(filters,
                        FilterStrategy.FEEDBACK_SCORES_AGGREGATED))
                .ifPresent(feedbackScoresAggregatedFilters -> template.add("feedback_scores_aggregated_filters",
                        feedbackScoresAggregatedFilters));
        Optional.ofNullable(criteria.filters())
                .flatMap(filters -> filterQueryBuilder.toAnalyticsDbFilters(filters,
                        FilterStrategy.FEEDBACK_SCORES_AGGREGATED_IS_EMPTY))
                .ifPresent(feedbackScoresAggregatedEmptyFilters -> template.add(
                        "feedback_scores_aggregated_empty_filters",
                        feedbackScoresAggregatedEmptyFilters));

        // Add experiment score filters
        Optional.ofNullable(criteria.filters())
                .flatMap(filters -> filterQueryBuilder.toAnalyticsDbFilters(filters,
                        FilterStrategy.EXPERIMENT_SCORES))
                .ifPresent(
                        experimentScoresFilters -> template.add("experiment_scores_filters", experimentScoresFilters));
        Optional.ofNullable(criteria.filters())
                .flatMap(filters -> filterQueryBuilder.toAnalyticsDbFilters(filters,
                        FilterStrategy.EXPERIMENT_SCORES_IS_EMPTY))
                .ifPresent(experimentScoresEmptyFilters -> template.add("experiment_scores_empty_filters",
                        experimentScoresEmptyFilters));

        return template;
    }

    private void bindSearchCriteria(Statement statement, ExperimentSearchCriteria criteria) {
        ExperimentSearchCriteriaBinder.bindSearchCriteria(
                statement,
                criteria,
                filterQueryBuilder,
                FILTER_STRATEGIES,
                false // Don't bind entity_type for aggregates
        );
    }

    private TraceAggregations createEmptyTraceAggregations(UUID experimentId) {
        return TraceAggregations.builder()
                .experimentId(experimentId)
                .projectId(ExperimentGroupMappers.ZERO_UUID)
                .durationPercentiles(Map.of())
                .traceCount(0L)
                .build();
    }

    private SpanAggregations createEmptySpanAggregations(UUID experimentId) {
        return SpanAggregations.builder()
                .experimentId(experimentId)
                .usageAvg(Map.of())
                .totalEstimatedCostSum(0.0)
                .totalEstimatedCostAvg(0.0)
                .totalEstimatedCostPercentiles(Map.of())
                .usageTotalTokensPercentiles(Map.of())
                .build();
    }

    private FeedbackScoreAggregations createEmptyFeedbackScoreAggregations(UUID experimentId) {
        return FeedbackScoreAggregations.builder()
                .experimentId(experimentId)
                .feedbackScoresPercentiles(Map.of())
                .feedbackScoresAvg(Map.of())
                .build();
    }

    private PassRateAggregation createEmptyPassRateAggregation(UUID experimentId) {
        return PassRateAggregation.builder()
                .experimentId(experimentId)
                .passRate(BigDecimal.ZERO)
                .passedCount(0L)
                .totalCount(0L)
                .build();
    }

    @Override
    public Flux<ExperimentGroupItem> findGroups(ExperimentGroupCriteria criteria) {
        return streamGroupQuery(FIND_GROUPS_FROM_AGGREGATES, criteria,
                ExperimentGroupMappers::toExperimentGroupItem);
    }

    @Override
    public Flux<ExperimentGroupAggregationItem> findGroupsAggregations(ExperimentGroupCriteria criteria) {
        return streamGroupQuery(FIND_GROUPS_AGGREGATIONS_FROM_AGGREGATES, criteria,
                ExperimentGroupMappers::toExperimentGroupAggregationItem);
    }

    private <T> Flux<T> streamGroupQuery(String queryTemplate, ExperimentGroupCriteria criteria,
            BiFunction<Row, Integer, T> rowMapper) {
        return asyncTemplate.stream(connection -> makeFluxContextAware((userName, workspaceId) -> {
            var template = newGroupTemplate(queryTemplate, criteria, workspaceId);

            var statement = connection.createStatement(template.render())
                    .bind("workspace_id", workspaceId);

            bindGroupCriteria(statement, criteria, filterQueryBuilder);
            if (Boolean.TRUE.equals(criteria.projectDeleted())) {
                statement.bind("zero_uuid", ExperimentGroupMappers.ZERO_UUID.toString());
            }

            int groupsCount = criteria.groups().size();

            return Flux.from(statement.execute())
                    .flatMap(result -> result.map((row, rowMetadata) -> rowMapper.apply(row, groupsCount)));
        }));
    }

    @Override
    public Mono<Long> countDatasetItemsWithExperimentItemsFromAggregates(
            @NonNull DatasetItemSearchCriteria criteria,
            @NonNull UUID versionId) {

        return asyncTemplate.nonTransaction(connection -> makeMonoContextAware((userName, workspaceId) -> {
            var template = getSTWithLogComment(
                    SELECT_DATASET_ITEM_VERSIONS_WITH_EXPERIMENT_ITEMS_COUNT,
                    "count_dataset_items_from_aggregates",
                    workspaceId,
                    criteria.datasetId().toString());

            applyDatasetItemFiltersToTemplate(template, criteria);

            var statement = connection.createStatement(template.render())
                    .bind("workspace_id", workspaceId)
                    .bind("dataset_id", criteria.datasetId().toString())
                    .bind("version_id", versionId.toString());

            bindDatasetItemSearchParams(statement, criteria);

            return Flux.from(statement.execute())
                    .flatMap(result -> result.map((row, rowMetadata) -> row.get("count", Long.class)))
                    .next();
        }));
    }

    @Override
    public Mono<DatasetItemPage> getDatasetItemsWithExperimentItemsFromAggregates(
            @NonNull DatasetItemSearchCriteria criteria,
            @NonNull UUID versionId,
            int page,
            int size) {

        var countMono = countDatasetItemsWithExperimentItemsFromAggregates(criteria, versionId);

        var itemsMono = asyncTemplate.nonTransaction(connection -> makeMonoContextAware((userName, workspaceId) -> {
            var template = getSTWithLogComment(
                    SELECT_DATASET_ITEM_VERSIONS_WITH_EXPERIMENT_ITEMS,
                    "get_dataset_items_from_aggregates",
                    workspaceId,
                    criteria.datasetId().toString());

            applyDatasetItemFiltersToTemplate(template, criteria);

            template.add("truncate", criteria.truncate());
            template.add("limit", true);
            template.add("offset", true);

            var statement = connection.createStatement(template.render())
                    .bind("workspace_id", workspaceId)
                    .bind("dataset_id", criteria.datasetId().toString())
                    .bind("version_id", versionId.toString())
                    .bind("limit", size)
                    .bind("offset", (page - 1) * size);

            bindDatasetItemSearchParams(statement, criteria);

            return Flux.from(statement.execute())
                    .flatMap(result -> result
                            .map((row, rowMeta) -> DatasetItemResultMapper.buildItemFromRow(row, rowMeta)))
                    .collectList();
        }));

        return countMono.flatMap(total -> {
            if (total == 0) {
                return Mono.just(DatasetItemPage.empty(page, List.of()));
            }
            return itemsMono.map(items -> new DatasetItemPage(items, page, items.size(), total, Set.of(), List.of()));
        });
    }

    private void applyDatasetItemFiltersToTemplate(ST template, DatasetItemSearchCriteria criteria) {
        if (CollectionUtils.isNotEmpty(criteria.experimentIds())) {
            template.add("experiment_ids", true);
        }

        DatasetItemSearchCriteriaMapper.applyToTemplate(template, criteria, DATASET_ITEM_FILTER_STRATEGY_PARAMS);
    }

    private void bindDatasetItemSearchParams(Statement statement, DatasetItemSearchCriteria criteria) {
        if (CollectionUtils.isNotEmpty(criteria.experimentIds())) {
            statement.bind("experiment_ids", criteria.experimentIds().toArray(UUID[]::new));
        }

        DatasetItemSearchCriteriaMapper.bindSearchCriteria(statement, criteria, DATASET_ITEM_BIND_STRATEGIES,
                filterQueryBuilder);
    }

    private ST newGroupTemplate(String query, ExperimentGroupCriteria criteria, String workspaceId) {
        var template = getSTWithLogComment(query, "find_groups_from_aggregates", workspaceId, "");
        ExperimentGroupMappers.applyGroupCriteriaToTemplate(template, criteria, filterQueryBuilder);
        groupingQueryBuilder.addGroupingTemplateParams(criteria.groups(), template);
        return template;
    }

    @Override
    public Mono<ProjectStats> getExperimentItemsStatsFromAggregates(
            @NonNull UUID datasetId,
            @NonNull UUID versionId,
            @NonNull Set<UUID> experimentIds,
            List<ExperimentsComparisonFilter> filters) {
        return asyncTemplate.nonTransaction(connection -> makeFluxContextAware((userName, workspaceId) -> {
            var template = getSTWithLogComment(SELECT_EXPERIMENT_ITEMS_STATS_FROM_AGGREGATES,
                    "getExperimentItemsStatsFromAggregates", workspaceId, datasetId);

            // Apply filters to template
            FilterQueryBuilder.applyFiltersToTemplate(template, filters,
                    EXPERIMENT_ITEMS_STATS_FILTER_STRATEGY_PARAMS);

            String sql = template.render();

            Statement statement = connection.createStatement(sql)
                    .bind("workspace_id", workspaceId)
                    .bind("dataset_id", datasetId.toString())
                    .bind("version_id", versionId.toString())
                    .bind("experiment_ids", experimentIds.toArray(UUID[]::new));

            // Bind filter parameters
            FilterQueryBuilder.bindFilters(statement, filters, EXPERIMENT_ITEMS_STATS_BIND_STRATEGIES);

            return Flux.from(statement.execute())
                    .flatMap(result -> result.map(
                            (row, rowMetadata) -> StatsMapper.mapExperimentItemsStats(row)));
        }).singleOrEmpty())
                .doOnError(error -> log.error("Failed to get experiment items stats from aggregates", error));
    }

    @Override
    public Mono<AggregatedExperimentCounts> getAggregationBranchCounts(
            @NonNull AggregationBranchCountsCriteria criteria) {
        return asyncTemplate.nonTransaction(connection -> Mono.deferContextual(ctx -> {
            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);

            var template = getSTWithLogComment(SELECT_EXPERIMENT_AGGREGATION_COUNTS,
                    "get_aggregation_branch_counts", workspaceId, criteria.datasetId());

            Optional.ofNullable(criteria.experimentIds())
                    .filter(CollectionUtils::isNotEmpty)
                    .ifPresent(experimentIds -> template.add("experiment_ids", experimentIds));
            Optional.ofNullable(criteria.datasetId())
                    .ifPresent(datasetId -> template.add("dataset_id", datasetId));
            Optional.ofNullable(criteria.id())
                    .ifPresent(id -> template.add("id", id));
            Optional.ofNullable(criteria.idsList())
                    .filter(CollectionUtils::isNotEmpty)
                    .ifPresent(idsList -> template.add("ids_list", idsList));

            var statement = connection.createStatement(template.render());

            Optional.ofNullable(criteria.experimentIds())
                    .filter(CollectionUtils::isNotEmpty)
                    .ifPresent(experimentIds -> statement.bind("experiment_ids",
                            experimentIds.toArray(UUID[]::new)));
            Optional.ofNullable(criteria.datasetId())
                    .ifPresent(datasetId -> statement.bind("dataset_id", datasetId));
            Optional.ofNullable(criteria.id())
                    .ifPresent(id -> statement.bind("id", id));
            Optional.ofNullable(criteria.idsList())
                    .filter(CollectionUtils::isNotEmpty)
                    .ifPresent(idsList -> statement.bind("ids_list", idsList.toArray(UUID[]::new)));

            return makeFluxContextAware(bindWorkspaceIdToFlux(statement))
                    .flatMap(result -> result
                            .map((row, metadata) -> new AggregatedExperimentCounts(
                                    row.get("aggregated", Long.class),
                                    row.get("not_aggregated", Long.class))))
                    .next()
                    .defaultIfEmpty(AggregatedExperimentCounts.BOTH_BRANCHES);
        }));
    }
}

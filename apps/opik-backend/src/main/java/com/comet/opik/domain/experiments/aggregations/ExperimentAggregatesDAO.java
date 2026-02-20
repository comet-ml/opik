package com.comet.opik.domain.experiments.aggregations;

import com.comet.opik.api.Experiment;
import com.comet.opik.api.ExperimentScore;
import com.comet.opik.api.ExperimentSearchCriteria;
import com.comet.opik.api.ExperimentStatus;
import com.comet.opik.api.ExperimentType;
import com.comet.opik.api.PercentageValues;
import com.comet.opik.domain.filter.FilterQueryBuilder;
import com.comet.opik.domain.filter.FilterStrategy;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.db.TransactionTemplateAsync;
import com.comet.opik.utils.JsonUtils;
import com.comet.opik.utils.template.TemplateUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.inject.ImplementedBy;
import io.r2dbc.spi.Result;
import io.r2dbc.spi.Row;
import io.r2dbc.spi.Statement;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.stream.Collectors;

import static com.comet.opik.domain.experiments.aggregations.ExperimentAggregatesModel.FeedbackScoreAggregations;
import static com.comet.opik.domain.experiments.aggregations.ExperimentAggregatesModel.SpanAggregations;
import static com.comet.opik.domain.experiments.aggregations.ExperimentAggregatesModel.TraceAggregations;
import static com.comet.opik.domain.experiments.aggregations.ExperimentAggregatesUtils.BatchResult;
import static com.comet.opik.domain.experiments.aggregations.ExperimentAggregatesUtils.MapArrays;
import static com.comet.opik.domain.experiments.aggregations.ExperimentEntityData.ExperimentData;
import static com.comet.opik.domain.experiments.aggregations.ExperimentEntityData.ExperimentItemData;
import static com.comet.opik.domain.experiments.aggregations.ExperimentSourceData.FeedbackScoreData;
import static com.comet.opik.domain.experiments.aggregations.ExperimentSourceData.SpanData;
import static com.comet.opik.domain.experiments.aggregations.ExperimentSourceData.TraceData;
import static com.comet.opik.infrastructure.DatabaseUtils.getSTWithLogComment;
import static com.comet.opik.utils.AsyncUtils.makeFluxContextAware;
import static com.comet.opik.utils.AsyncUtils.makeMonoContextAware;
import static com.comet.opik.utils.template.TemplateUtils.getQueryItemPlaceHolder;

@ImplementedBy(ExperimentAggregatesDAOImpl.class)
public interface ExperimentAggregatesDAO {

    Mono<Void> populateExperimentAggregate(UUID experimentId);

    Mono<BatchResult> populateExperimentItemAggregates(UUID experimentId, UUID cursor, int limit);

    Mono<Experiment> getExperimentFromAggregates(UUID experimentId);

    Mono<Long> countTotal(ExperimentSearchCriteria experimentSearchCriteria,
            Set<UUID> targetProjectIds);
}

@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Slf4j
class ExperimentAggregatesDAOImpl implements ExperimentAggregatesDAO {

    public static final TypeReference<List<ExperimentScore>> TYPE_REFERENCE = new TypeReference<>() {
    };

    private final @NonNull TransactionTemplateAsync asyncTemplate;
    private final @NonNull FilterQueryBuilder filterQueryBuilder;

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
                usage_avg
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
                status,
                experiment_scores
            FROM experiments FINAL
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
                SELECT
                    DISTINCT trace_id
                FROM experiment_items FINAL
                WHERE workspace_id = :workspace_id
                AND experiment_id = :experiment_id
            )
            SELECT DISTINCT project_id
            FROM traces FINAL
            WHERE workspace_id = :workspace_id
            AND id IN (SELECT trace_id FROM experiment_trace_items)
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
                FROM experiment_items FINAL
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
                AND id IN (SELECT trace_id FROM experiment_trace_items)
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
                SELECT trace_id
                FROM experiment_items FINAL
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
                SELECT trace_id
                FROM experiment_items FINAL
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
                usage_total_tokens_percentiles
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
                mapFromArrays(:usage_total_tokens_percentiles_keys, :usage_total_tokens_percentiles_values)
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
                dataset_item_id
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
                duration
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
            ), feedback_scores_combined AS (
                SELECT
                    entity_id,
                    name,
                    value
                FROM feedback_scores FINAL
                WHERE entity_type = 'trace'
                AND workspace_id = :workspace_id
                AND project_id = :project_id
                AND entity_id IN (SELECT trace_id FROM experiment_items)
                UNION ALL
                SELECT
                    entity_id,
                    name,
                    value
                FROM authored_feedback_scores FINAL
                WHERE entity_type = 'trace'
                AND workspace_id = :workspace_id
                AND project_id = :project_id
                AND entity_id IN (SELECT trace_id FROM experiment_items)
            ), feedback_scores_final AS (
                SELECT
                    entity_id,
                    name,
                    if(count() = 1, any(value), toDecimal64(avg(value), 9)) AS value
                FROM feedback_scores_combined
                WHERE length(name) > 0
                GROUP BY entity_id, name
            )
            SELECT
                entity_id as trace_id,
                mapFromArrays(
                    groupArray(name),
                    groupArray(value)
                ) AS feedback_scores
            FROM feedback_scores_final
            GROUP BY entity_id
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
                duration,
                total_estimated_cost,
                usage,
                feedback_scores,
                created_at,
                last_updated_at
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
                    :duration<item.index>,
                    :total_estimated_cost<item.index>,
                    if(:has_usage<item.index>, mapFromArrays(:usage_keys<item.index>, :usage_values<item.index>), CAST(map() AS Map(String, Int64)) ),
                    if(:has_feedback_scores<item.index>, mapFromArrays(:feedback_scores_keys<item.index>, CAST(:feedback_scores_values<item.index> AS Array(Decimal64(9)))), CAST(map() AS Map(String, Decimal64(9))) ),
                    parseDateTime64BestEffort(:created_at<item.index>, 9),
                    parseDateTime64BestEffort(:last_updated_at<item.index>, 9)
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
            <if(project_deleted)> AND (has(ep.project_ids, '') OR empty(ep.project_ids)) <endif>
            SETTINGS log_comment = '<log_comment>'
            ;
            """;

    @Override
    public Mono<Void> populateExperimentAggregate(UUID experimentId) {

        return Mono.deferContextual(ctx -> {
            String workspaceId = ctx.get("workspaceId");

            log.info("Populating experiment_aggregates for experiment: '{}' in workspace: '{}'",
                    experimentId, workspaceId);

            return getExperimentData(experimentId)
                    .flatMap(experimentData -> {
                        // First check if experiment has any items
                        return getExperimentItemsCount(experimentId)
                                .flatMap(itemsCount -> {
                                    if (itemsCount == 0) {
                                        // Handle experiments with zero items - insert empty aggregate row
                                        log.info("Experiment '{}' has no items, inserting empty aggregate",
                                                experimentId);
                                        return insertExperimentAggregate(
                                                experimentData,
                                                createEmptyTraceAggregations(experimentId),
                                                createEmptySpanAggregations(experimentId),
                                                createEmptyFeedbackScoreAggregations(experimentId),
                                                0L);
                                    }

                                    // Get project_id for experiments with items
                                    return getProjectId(experimentId)
                                            .flatMap(projectId -> {
                                                // Fetch aggregations using project_id for filtering
                                                return Mono.zip(
                                                        getTraceAggregations(experimentId, projectId),
                                                        getSpanAggregations(experimentId, projectId),
                                                        getFeedbackScoreAggregations(experimentId, projectId))
                                                        .flatMap(tuple -> {
                                                            var traceAgg = tuple.getT1();
                                                            var spanAgg = tuple.getT2();
                                                            var feedbackAgg = tuple.getT3();

                                                            return insertExperimentAggregate(
                                                                    experimentData,
                                                                    traceAgg,
                                                                    spanAgg,
                                                                    feedbackAgg,
                                                                    itemsCount);
                                                        });
                                            });
                                });
                    })
                    .doOnSuccess(v -> log.info(
                            "Successfully populated experiment_aggregates for experiment: '{}' in workspace: '{}'",
                            experimentId, workspaceId))
                    .doOnError(error -> log.error(
                            "Failed to populate experiment_aggregates for experiment: '{}' in workspace: '{}'",
                            experimentId, workspaceId, error));
        });
    }

    @Override
    public Mono<BatchResult> populateExperimentItemAggregates(UUID experimentId, UUID cursorId, int limit) {

        return Mono.deferContextual(ctx -> {
            String workspaceId = ctx.get("workspaceId");

            log.info(
                    "Populating experiment_item_aggregates for experiment: '{}' in workspace: '{}', cursorId: '{}', limit: '{}'",
                    experimentId, workspaceId, cursorId, limit);

            return getExperimentItems(experimentId, cursorId, limit)
                    .collectList()
                    .flatMap(items -> {
                        if (items.isEmpty()) {
                            log.info("No experiment items found for experiment: '{}', cursorId: '{}'",
                                    experimentId, cursorId);
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
                                                .collectList())
                                        .flatMap(tuple -> {
                                            var tracesData = tuple.getT1();
                                            var spansData = tuple.getT2();
                                            var feedbackData = tuple.getT3();

                                            return insertExperimentItemAggregates(
                                                    projectId,
                                                    items,
                                                    tracesData,
                                                    spansData,
                                                    feedbackData).map(count -> new BatchResult(count, lastCursor));
                                        }));
                    })
                    .doOnSuccess(result -> log.info(
                            "Successfully populated '{}' experiment_item_aggregates for experiment: '{}', cursorId: '{}', lastCursor: '{}'",
                            result.processedCount(), experimentId, cursorId, result.lastCursor()))
                    .doOnError(error -> log.error(
                            "Failed to populate experiment_item_aggregates for experiment: '{}', cursorId: '{}'",
                            experimentId, cursorId, error));
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
        return asyncTemplate.nonTransaction(connection -> makeFluxContextAware((userName, workspaceId) -> {
            var template = getSTWithLogComment(GET_TRACE_AGGREGATIONS,
                    "getTraceAggregations", workspaceId, experimentId.toString());

            var statement = connection.createStatement(template.render())
                    .bind("workspace_id", workspaceId)
                    .bind("experiment_id", experimentId)
                    .bind("project_id", projectId);

            return Flux.from(statement.execute())
                    .flatMap(result -> result.map((row, metadata) -> mapTraceAggregations(row)));
        }).singleOrEmpty());
    }

    private Mono<SpanAggregations> getSpanAggregations(UUID experimentId, UUID projectId) {
        return asyncTemplate.nonTransaction(connection -> makeFluxContextAware((userName, workspaceId) -> {
            var template = getSTWithLogComment(GET_SPAN_AGGREGATIONS,
                    "getSpanAggregations", workspaceId, experimentId.toString());

            var statement = connection.createStatement(template.render())
                    .bind("workspace_id", workspaceId)
                    .bind("experiment_id", experimentId)
                    .bind("project_id", projectId);

            return Flux.from(statement.execute())
                    .flatMap(result -> result.map((row, metadata) -> mapSpanAggregations(row)));
        }).singleOrEmpty());
    }

    private Mono<FeedbackScoreAggregations> getFeedbackScoreAggregations(UUID experimentId,
            UUID projectId) {
        return asyncTemplate.nonTransaction(connection -> makeFluxContextAware((userName, workspaceId) -> {
            var template = getSTWithLogComment(GET_FEEDBACK_SCORE_AGGREGATIONS,
                    "getFeedbackScoreAggregations", workspaceId, experimentId.toString());

            var statement = connection.createStatement(template.render())
                    .bind("workspace_id", workspaceId)
                    .bind("experiment_id", experimentId)
                    .bind("project_id", projectId);

            return Flux.from(statement.execute())
                    .flatMap(result -> result.map((row, metadata) -> mapFeedbackScoreAggregations(row)));
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
            long itemsCount) {

        return asyncTemplate.nonTransaction(connection -> {
            var template = getSTWithLogComment(INSERT_EXPERIMENT_AGGREGATE,
                    "insertExperimentAggregate", experimentData.workspaceId(), experimentData.id().toString());

            // Convert Maps to key/value arrays for ClickHouse mapFromArrays
            var experimentScoresArrays = mapToArrays(
                    defaultIfNull(experimentData.experimentScores(), Map.of()),
                    String[]::new, BigDecimal[]::new,
                    v -> BigDecimal.valueOf(v.doubleValue()));
            var durationPercentilesArrays = mapToArrays(
                    defaultIfNull(traceAgg.durationPercentiles(), Map.of()),
                    String[]::new, Double[]::new,
                    v -> v);
            var totalEstimatedCostPercentilesArrays = mapToArrays(
                    defaultIfNull(spanAgg.totalEstimatedCostPercentiles(), Map.of()),
                    String[]::new, Double[]::new,
                    v -> v);
            var usageAvgArrays = mapToArrays(
                    defaultIfNull(spanAgg.usageAvg(), Map.of()),
                    String[]::new, Double[]::new,
                    Double::doubleValue);
            Map<String, Double> usageTotalTokensPercentiles = defaultIfNull(spanAgg.usageTotalTokensPercentiles(),
                    Map.of());
            var usageTotalTokensPercentilesArrays = mapToArrays(
                    usageTotalTokensPercentiles,
                    String[]::new, Double[]::new,
                    v -> (v).doubleValue());
            var feedbackScoresAvgArrays = mapToArrays(
                    defaultIfNull(feedbackAgg.feedbackScoresAvg(), Map.of()),
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
                    .bind("metadata", defaultIfNull(experimentData.metadata(), ""))
                    .bind("prompt_versions", defaultIfNull(experimentData.promptVersions(), Map.of()))
                    .bind("optimization_id", defaultIfNull(experimentData.optimizationId(), ""))
                    .bind("dataset_version_id", defaultIfNull(experimentData.datasetVersionId(), ""))
                    .bind("tags", experimentData.tags().toArray(new String[0]))
                    .bind("type", experimentData.type())
                    .bind("status", experimentData.status())
                    .bind("experiment_scores_keys", experimentScoresArrays.keys())
                    .bind("experiment_scores_values", experimentScoresArrays.values())
                    .bind("trace_count", traceAgg.traceCount())
                    .bind("experiment_items_count", itemsCount)
                    .bind("duration_percentiles_keys", durationPercentilesArrays.keys())
                    .bind("duration_percentiles_values", durationPercentilesArrays.values())
                    .bind("feedback_scores_percentiles",
                            defaultIfNull(feedbackAgg.feedbackScoresPercentiles(), Map.of()))
                    .bind("total_estimated_cost_sum", spanAgg.totalEstimatedCostSum())
                    .bind("total_estimated_cost_avg", spanAgg.totalEstimatedCostAvg())
                    .bind("total_estimated_cost_percentiles_keys", totalEstimatedCostPercentilesArrays.keys())
                    .bind("total_estimated_cost_percentiles_values", totalEstimatedCostPercentilesArrays.values())
                    .bind("usage_avg_keys", usageAvgArrays.keys())
                    .bind("usage_avg_values", usageAvgArrays.values())
                    .bind("usage_total_tokens_percentiles_keys", usageTotalTokensPercentilesArrays.keys())
                    .bind("usage_total_tokens_percentiles_values", usageTotalTokensPercentilesArrays.values())
                    .bind("feedback_scores_avg_keys", feedbackScoresAvgArrays.keys())
                    .bind("feedback_scores_avg_values", feedbackScoresAvgArrays.values());

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

    private Flux<TraceData> getTracesData(String workspaceId, UUID experimentId, UUID projectId,
            UUID cursor, int limit) {
        return asyncTemplate.stream(connection -> {
            var template = getSTWithLogComment(GET_TRACES_DATA,
                    "getTracesData", workspaceId, experimentId.toString())
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
                    .flatMap(result -> result.map((row, metadata) -> mapTraceData(row)));
        });
    }

    private Flux<SpanData> getSpansData(String workspaceId, UUID experimentId, UUID projectId,
            UUID cursor, int limit) {
        return asyncTemplate.stream(connection -> {
            var template = getSTWithLogComment(GET_SPANS_DATA,
                    "getSpansData", workspaceId, experimentId.toString())
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
                    .flatMap(result -> result.map((row, metadata) -> mapSpanData(row)));
        });
    }

    private Flux<FeedbackScoreData> getFeedbackScoresData(String workspaceId, UUID experimentId, UUID projectId,
            UUID cursor, int limit) {
        return asyncTemplate.stream(connection -> {
            var template = getSTWithLogComment(GET_FEEDBACK_SCORES_DATA,
                    "getFeedbackScoresData", workspaceId, experimentId.toString())
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
                    .flatMap(result -> result.map((row, metadata) -> mapFeedbackScoreData(row)));
        });
    }

    private void bindItemsParameters(Statement statement,
            List<ExperimentItemData> items,
            Map<UUID, TraceData> tracesMap,
            Map<UUID, SpanData> spansMap,
            Map<UUID, FeedbackScoreData> feedbackMap) {

        for (int i = 0; i < items.size(); i++) {
            var item = items.get(i);

            TraceData trace = tracesMap.get(item.traceId());
            SpanData span = spansMap.get(item.traceId());
            FeedbackScoreData feedback = feedbackMap.get(item.traceId());

            Map<String, Long> usageMap = span != null && span.usage() != null ? span.usage() : Map.of();
            Map<String, BigDecimal> feedbackScoresMap = feedback != null
                    && feedback.feedbackScores() != null ? feedback.feedbackScores() : Map.of();

            statement.bind("id" + i, item.id())
                    .bind("has_usage" + i, !usageMap.isEmpty())
                    .bind("has_feedback_scores" + i, !feedbackScoresMap.isEmpty())
                    .bind("experiment_id" + i, item.experimentId())
                    .bind("dataset_item_id" + i, item.datasetItemId())
                    .bind("trace_id" + i, item.traceId())
                    .bind("duration" + i, trace != null ? trace.duration() : BigDecimal.ZERO)
                    .bind("total_estimated_cost" + i,
                            span != null ? span.totalEstimatedCost() : BigDecimal.ZERO)
                    .bind("created_at" + i, java.time.Instant.now().toString())
                    .bind("last_updated_at" + i, java.time.Instant.now().toString());

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
            List<FeedbackScoreData> feedbackData) {

        // Create lookup maps
        Map<UUID, TraceData> tracesMap = tracesData.stream()
                .collect(Collectors.toMap(TraceData::traceId, t -> t));
        Map<UUID, SpanData> spansMap = spansData.stream()
                .collect(Collectors.toMap(SpanData::traceId, s -> s));
        Map<UUID, FeedbackScoreData> feedbackMap = feedbackData.stream()
                .collect(Collectors.toMap(FeedbackScoreData::traceId, f -> f));

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
                bindItemsParameters(statement, items, tracesMap, spansMap, feedbackMap);

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
                .tags(row.get("tags", List.class))
                .type(row.get("type", String.class))
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
        Map<String, Double> feedbackScoresAvg = feedbackScoresAvgRaw != null
                ? feedbackScoresAvgRaw.entrySet().stream()
                        .collect(java.util.stream.Collectors.toMap(
                                Map.Entry::getKey,
                                e -> ((Number) e.getValue()).doubleValue()))
                : null;

        return FeedbackScoreAggregations.builder()
                .experimentId(getUUID(row, "experiment_id"))
                .feedbackScoresPercentiles(row.get("feedback_scores_percentiles", Map.class))
                .feedbackScoresAvg(feedbackScoresAvg)
                .build();
    }

    private ExperimentItemData mapExperimentItemData(Row row) {
        return ExperimentItemData.builder()
                .id(getUUID(row, "id"))
                .experimentId(getUUID(row, "experiment_id"))
                .traceId(getUUID(row, "trace_id"))
                .datasetItemId(getUUID(row, "dataset_item_id"))
                .build();
    }

    private <T> T defaultIfNull(T value, T defaultValue) {
        return value != null ? value : defaultValue;
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

                var statement = connection.createStatement(SELECT_EXPERIMENT_BY_ID)
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
        List<ExperimentScore> experimentScores = experimentScoresRaw != null
                ? experimentScoresRaw.entrySet().stream()
                        .map(e -> new ExperimentScore(e.getKey(), BigDecimal.valueOf(e.getValue())))
                        .toList()
                : null;

        // trace_count is UInt64 in ClickHouse, read as Long
        Long traceCount = row.get("trace_count", Long.class);

        // duration_percentiles is Map(String, Float64), read as Map<String, Double>
        Map<String, Double> durationMap = row.get("duration_percentiles", Map.class);
        PercentageValues duration = durationMap != null && !durationMap.isEmpty()
                ? new PercentageValues(
                        BigDecimal.valueOf(durationMap.getOrDefault("p50", 0.0)),
                        BigDecimal.valueOf(durationMap.getOrDefault("p90", 0.0)),
                        BigDecimal.valueOf(durationMap.getOrDefault("p99", 0.0)))
                : null;

        // feedback_scores_avg is Map(String, Float64), convert to List<FeedbackScoreAverage>
        Map<String, Double> feedbackScoresAvgRaw = row.get("feedback_scores_avg", Map.class);
        List<com.comet.opik.api.FeedbackScoreAverage> feedbackScores = feedbackScoresAvgRaw != null
                ? feedbackScoresAvgRaw.entrySet().stream()
                        .map(e -> new com.comet.opik.api.FeedbackScoreAverage(e.getKey(),
                                BigDecimal.valueOf(e.getValue())))
                        .toList()
                : null;

        // total_estimated_cost_sum is Float64, read as Double
        BigDecimal totalEstimatedCost = getBigDecimal(row, "total_estimated_cost_sum");

        // total_estimated_cost_avg is Float64, read as Double
        BigDecimal totalEstimatedCostAvg = getBigDecimal(row, "total_estimated_cost_avg");

        // usage_avg is Map(String, Float64), read as Map<String, Double>
        Map<String, Double> usageAvg = row.get("usage_avg", Map.class);

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
                optimizationId,
                feedbackScores,
                null, // comments - not in DB
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
                null); // datasetVersionSummary - not in DB
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

    /**
     * Helper to convert Double to BigDecimal.
     */
    private BigDecimal getBigDecimal(Row row, String columnName) {
        Double value = row.get(columnName, Double.class);
        return value != null ? BigDecimal.valueOf(value) : null;
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
        return TraceData.builder()
                .traceId(getUUID(row, "trace_id"))
                .projectId(getUUID(row, "project_id"))
                .duration(row.get("duration", BigDecimal.class))
                .build();
    }

    private SpanData mapSpanData(Row row) {
        return SpanData.builder()
                .traceId(getUUID(row, "trace_id"))
                .usage(row.get("usage", Map.class))
                .totalEstimatedCost(row.get("total_estimated_cost", BigDecimal.class))
                .build();
    }

    private FeedbackScoreData mapFeedbackScoreData(Row row) {
        return FeedbackScoreData.builder()
                .traceId(getUUID(row, "trace_id"))
                .feedbackScores(row.get("feedback_scores", Map.class))
                .build();
    }

    @Override
    public Mono<Long> countTotal(ExperimentSearchCriteria experimentSearchCriteria,
            Set<UUID> targetProjectIds) {
        return asyncTemplate.nonTransaction(connection -> countTotalFromAggregates(experimentSearchCriteria, connection,
                targetProjectIds)
                .flatMap(result -> Flux.from(result.map((row, rowMetadata) -> row.get("count", Long.class))))
                .reduce(0L, Long::sum));
    }

    private Flux<? extends Result> countTotalFromAggregates(
            ExperimentSearchCriteria experimentSearchCriteria, io.r2dbc.spi.Connection connection,
            Set<UUID> targetProjectIds) {
        log.info("Counting experiments from aggregates by '{}'", experimentSearchCriteria);
        return makeFluxContextAware((userName, workspaceId) -> {
            var template = buildCountTemplate(experimentSearchCriteria, workspaceId, targetProjectIds);

            var statement = connection.createStatement(template.render())
                    .bind("workspace_id", workspaceId);

            // Bind target project IDs (from separate query to reduce table scans)
            if (targetProjectIds != null && !targetProjectIds.isEmpty()) {
                statement.bind("target_project_ids", targetProjectIds.toArray(UUID[]::new));
            }

            bindSearchCriteria(statement, experimentSearchCriteria);
            return Flux.from(statement.execute());
        });
    }

    private org.stringtemplate.v4.ST buildCountTemplate(ExperimentSearchCriteria criteria, String workspaceId,
            Set<UUID> targetProjectIds) {
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

        // Add target project IDs flag to template (from separate query to reduce table scans)
        if (targetProjectIds != null && !targetProjectIds.isEmpty()) {
            template.add("has_target_projects", true);
        }

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
        Optional.ofNullable(criteria.datasetId())
                .ifPresent(datasetId -> statement.bind("dataset_id", datasetId));
        Optional.ofNullable(criteria.name())
                .ifPresent(name -> statement.bind("name", name));
        Optional.ofNullable(criteria.datasetIds())
                .ifPresent(datasetIds -> statement.bind("dataset_ids", datasetIds.toArray(UUID[]::new)));
        Optional.ofNullable(criteria.promptId())
                .ifPresent(promptId -> statement.bind("prompt_ids", List.of(promptId).toArray(UUID[]::new)));
        Optional.ofNullable(criteria.projectId())
                .ifPresent(projectId -> statement.bind("project_id", projectId));
        Optional.ofNullable(criteria.optimizationId())
                .ifPresent(optimizationId -> statement.bind("optimization_id", optimizationId));
        Optional.ofNullable(criteria.types())
                .filter(types -> types != null && !types.isEmpty())
                .ifPresent(types -> statement.bind("types", types));
        Optional.ofNullable(criteria.experimentIds())
                .filter(experimentIds -> experimentIds != null && !experimentIds.isEmpty())
                .ifPresent(experimentIds -> statement.bind("experiment_ids", experimentIds.toArray(UUID[]::new)));

        // Bind filters (ONLY FEEDBACK_SCORES_AGGREGATED referenced here in ExperimentAggregatesDAO)
        Optional.ofNullable(criteria.filters())
                .ifPresent(filters -> {
                    filterQueryBuilder.bind(statement, filters, FilterStrategy.EXPERIMENT);
                    filterQueryBuilder.bind(statement, filters, FilterStrategy.FEEDBACK_SCORES_AGGREGATED);
                    filterQueryBuilder.bind(statement, filters, FilterStrategy.FEEDBACK_SCORES_AGGREGATED_IS_EMPTY);
                    filterQueryBuilder.bind(statement, filters, FilterStrategy.EXPERIMENT_SCORES);
                    filterQueryBuilder.bind(statement, filters, FilterStrategy.EXPERIMENT_SCORES_IS_EMPTY);
                });
    }

    private TraceAggregations createEmptyTraceAggregations(UUID experimentId) {
        return TraceAggregations.builder()
                .experimentId(experimentId)
                .projectId(UUID.fromString("00000000-0000-0000-0000-000000000000"))
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
}

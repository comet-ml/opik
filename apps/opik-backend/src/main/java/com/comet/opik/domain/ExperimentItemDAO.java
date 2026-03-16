package com.comet.opik.domain;

import com.comet.opik.api.ExperimentItem;
import com.comet.opik.api.ExperimentStatus;
import com.comet.opik.domain.experiments.aggregations.AggregatedExperimentCounts;
import com.comet.opik.domain.experiments.aggregations.AggregationBranchCountsCriteria;
import com.comet.opik.domain.experiments.aggregations.ExperimentAggregatesDAO;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.utils.template.TemplateUtils;
import com.google.common.base.Preconditions;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.Result;
import io.r2dbc.spi.Statement;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.comet.opik.domain.AsyncContextUtils.bindWorkspaceIdToFlux;
import static com.comet.opik.infrastructure.DatabaseUtils.getSTWithLogComment;
import static com.comet.opik.utils.AsyncUtils.makeFluxContextAware;
import static com.comet.opik.utils.AsyncUtils.makeMonoContextAware;
import static com.comet.opik.utils.template.TemplateUtils.QueryItem;
import static com.comet.opik.utils.template.TemplateUtils.getQueryItemPlaceHolder;

@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Slf4j
class ExperimentItemDAO {

    record ExperimentSummary(UUID datasetId, long experimentCount, Instant mostRecentExperimentAt) {
        public static ExperimentSummary empty(UUID datasetId) {
            return new ExperimentSummary(datasetId, 0, null);
        }
    }

    /**
     * The query validates if already exists with this id. Failing if so.
     * That way only insert is allowed, but not update.
     */
    private static final String INSERT = """
            INSERT INTO experiment_items (
                id,
                experiment_id,
                dataset_item_id,
                trace_id,
                workspace_id,
                project_id,
                created_by,
                last_updated_by,
                execution_policy
            )
            SETTINGS log_comment = '<log_comment>'
            FORMAT Values
                  <items:{item |
                     (
                        :id<item.index>,
                        :experiment_id<item.index>,
                        :dataset_item_id<item.index>,
                        :trace_id<item.index>,
                        :workspace_id,
                        :project_id<item.index>,
                        :created_by<item.index>,
                        :last_updated_by<item.index>,
                        :execution_policy<item.index>
                    )
                     <if(item.hasNext)>
                        ,
                     <endif>
                  }>
            ;
            """;

    private static final String SELECT = """
            SELECT
            *
            FROM experiment_items
            WHERE id = :id
            AND workspace_id = :workspace_id
            ORDER BY last_updated_at DESC
            LIMIT 1
            SETTINGS log_comment = '<log_comment>'
            ;
            """;

    private static final String SELECT_TARGET_PROJECTS = """
            WITH experiment_items_trace_scope AS (
                SELECT DISTINCT trace_id
                FROM experiment_items
                WHERE workspace_id = :workspace_id
                AND experiment_id IN :experiment_ids
            )
            SELECT DISTINCT project_id
            FROM traces
            WHERE workspace_id = :workspace_id
            AND id IN (SELECT DISTINCT trace_id FROM experiment_items_trace_scope)
            SETTINGS log_comment = '<log_comment>'
            ;
            """;

    private static final String STREAM = """
            WITH experiment_aggregated_scope_ids AS (
                SELECT
                    id
                FROM experiment_aggregates
                WHERE workspace_id = :workspace_id
                AND id IN :experiment_ids
            ), experiment_items_ids AS (
                SELECT
                    DISTINCT id, trace_id
                FROM experiment_items
                WHERE workspace_id = :workspace_id
                AND experiment_id IN :experiment_ids
                AND experiment_id NOT IN (SELECT id FROM experiment_aggregated_scope_ids)
                <if(lastRetrievedId)> AND id \\< :lastRetrievedId <endif>
                ORDER BY id DESC, last_updated_at DESC
                LIMIT 1 BY id
                <if(has_raw && !has_aggregated)>LIMIT :limit<endif>
            ), experiment_item_aggregates_final AS (
                SELECT
                    *
                FROM experiment_item_aggregates AS eia
                WHERE eia.workspace_id = :workspace_id
                AND eia.experiment_id IN :experiment_ids
                AND eia.experiment_id IN (SELECT id FROM experiment_aggregated_scope_ids)
                <if(lastRetrievedId)> AND eia.id \\< :lastRetrievedId <endif>
                ORDER BY eia.id DESC, eia.last_updated_at DESC
                LIMIT 1 BY eia.id
                <if(has_aggregated && !has_raw)>LIMIT :limit<endif>
            ), experiment_items_scope AS (
                SELECT
                    *
                FROM experiment_items
                WHERE workspace_id = :workspace_id
                AND experiment_id IN :experiment_ids
                AND id IN (SELECT id FROM experiment_items_ids)
                ORDER BY id DESC, last_updated_at DESC
                LIMIT 1 BY id
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
                  FROM feedback_scores
                  WHERE entity_type = 'trace'
                    AND workspace_id = :workspace_id
                    <if(has_target_projects)>AND project_id IN :target_project_ids<endif>
                    AND entity_id IN (SELECT trace_id FROM experiment_items_ids)
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
                  FROM authored_feedback_scores
                  WHERE entity_type = 'trace'
                    AND workspace_id = :workspace_id
                    <if(has_target_projects)>AND project_id IN :target_project_ids<endif>
                    AND entity_id IN (SELECT trace_id FROM experiment_items_ids)
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
                      arrayStringConcat(reasons, ', ') AS reason,
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
            ), comments_final AS (
                  SELECT
                      id AS comment_id,
                      text,
                      created_at AS comment_created_at,
                      last_updated_at AS comment_last_updated_at,
                      created_by AS comment_created_by,
                      last_updated_by AS comment_last_updated_by,
                      entity_id
                  FROM comments
                  WHERE workspace_id = :workspace_id
                  <if(has_target_projects)>AND project_id IN :target_project_ids<endif>
                  AND entity_id IN (SELECT trace_id FROM experiment_items_ids)
                  ORDER BY (workspace_id, project_id, entity_id, id) DESC, last_updated_at DESC
                  LIMIT 1 BY id
            ), feedback_scores_per_trace AS (
                  SELECT
                      entity_id,
                      toJSONString(
                          groupUniqArray(
                              CAST(
                                  (
                                      name,
                                      category_name,
                                      value,
                                      reason,
                                      toString(source),
                                      concat(replaceOne(toString(created_at), ' ', 'T'), 'Z'),
                                      concat(replaceOne(toString(last_updated_at), ' ', 'T'), 'Z'),
                                      created_by,
                                      last_updated_by,
                                      mapFromArrays(
                                      mapKeys(value_by_author),
                                      arrayMap(
                                          v -> CAST(
                                              (
                                                  v.1,
                                                  v.2,
                                                  v.3,
                                                  toString(v.4),
                                                  concat(replaceOne(toString(v.5), ' ', 'T'), 'Z')
                                              ),
                                              'Tuple(
                                                  value Decimal(18,9),
                                                  reason String,
                                                  category_name String,
                                                  source String,
                                                  last_updated_at String
                                              )'
                                          ),
                                          mapValues(value_by_author)
                                      )
                                  )
                                  ),
                                  'Tuple(
                                      name String,
                                      category_name String,
                                      value Decimal(18,9),
                                      reason String,
                                      source String,
                                      created_at String,
                                      last_updated_at String,
                                      created_by String,
                                      last_updated_by String,
                                      value_by_author Map(
                                          String,
                                          Tuple(
                                              value Decimal(18,9),
                                              reason String,
                                              category_name String,
                                              source String,
                                              last_updated_at String
                                          )
                                      )
                                  )'
                              )
                          )
                      ) AS feedback_scores_array
                  FROM feedback_scores_final
                  GROUP BY entity_id
            ), comments_per_trace AS (
                  SELECT
                      entity_id,
                      toJSONString(groupUniqArray(CAST(
                          (comment_id, text,
                           concat(replaceOne(toString(comment_created_at), ' ', 'T'), 'Z'),
                           concat(replaceOne(toString(comment_last_updated_at), ' ', 'T'), 'Z'),
                           comment_created_by, comment_last_updated_by, entity_id),
                          'Tuple(
                              id FixedString(36),
                              text String,
                              created_at String,
                              last_updated_at String,
                              created_by String,
                              last_updated_by String,
                              entity_id FixedString(36)
                          )'
                      ))) AS comments_array_agg
                  FROM comments_final
                  GROUP BY entity_id
            )
              SELECT
                  *
              FROM (
                  <if(has_aggregated)>
                  -- Branch 1: pre-computed values from experiment_item_aggregates (COMPLETED/CANCELLED experiments)
                  SELECT
                      ei.id AS id,
                      ei.experiment_id  AS experiment_id,
                      ei.dataset_item_id AS dataset_item_id,
                      ei.trace_id   AS trace_id,
                      ei.project_id AS project_id,
                      <if(truncate)> replaceRegexpAll(if(notEmpty(ei.input_slim), ei.input_slim, ei.input), '<truncate>', '"[image]"') <else> ei.input <endif> AS input,
                      <if(truncate)> replaceRegexpAll(if(notEmpty(ei.output_slim), ei.output_slim, ei.output), '<truncate>', '"[image]"') <else> ei.output <endif> AS output,
                      ei.feedback_scores_array AS feedback_scores_array,
                      ei.comments_array_agg AS comments_array_agg,
                      ei.total_estimated_cost AS total_estimated_cost,
                      ei.usage AS usage,
                      ei.duration AS duration,
                      ei.created_at AS created_at,
                      ei.last_updated_at AS last_updated_at,
                      ei.created_by AS created_by,
                      ei.last_updated_by AS last_updated_by,
                      ei.visibility_mode AS trace_visibility_mode,
                      ei.execution_policy
                  FROM experiment_item_aggregates_final AS ei
                  <endif>

                  <if(has_aggregated)><if(has_raw)>UNION ALL<endif><endif>

                  <if(has_raw)>
                  -- Branch 2: on-the-fly computation via JOINs for experiments not in aggregates
                  SELECT
                      ei.id AS id,
                      ei.experiment_id AS experiment_id,
                      ei.dataset_item_id AS dataset_item_id,
                      ei.trace_id AS trace_id,
                      ei.project_id AS project_id,
                      tfs.input AS input,
                      tfs.output AS output,
                      fsp.feedback_scores_array AS feedback_scores_array,
                      cp.comments_array_agg AS comments_array_agg,
                      tfs.total_estimated_cost AS total_estimated_cost,
                      tfs.usage AS usage,
                      tfs.duration AS duration,
                      ei.created_at AS created_at,
                      ei.last_updated_at AS last_updated_at,
                      ei.created_by AS created_by,
                      ei.last_updated_by AS last_updated_by,
                      tfs.visibility_mode AS trace_visibility_mode,
                      ei.execution_policy
                  FROM experiment_items_scope AS ei
                  LEFT JOIN (
                      SELECT
                          t.id,
                          t.input,
                          t.output,
                          t.duration,
                          t.visibility_mode,
                          s.total_estimated_cost,
                          s.usage
                      FROM (
                          SELECT
                              id,
                              duration,
                              <if(truncate)> replaceRegexpAll(if(notEmpty(input_slim), input_slim, truncated_input), '<truncate>', '"[image]"') as input <else> input <endif>,
                              <if(truncate)> replaceRegexpAll(if(notEmpty(output_slim), output_slim, truncated_output), '<truncate>', '"[image]"') as output <else> output <endif>,
                              visibility_mode
                          FROM traces
                          WHERE workspace_id = :workspace_id
                          <if(has_target_projects)>AND project_id IN :target_project_ids<endif>
                          AND id IN (SELECT trace_id FROM experiment_items_ids)
                          ORDER BY (workspace_id, project_id, id) DESC, last_updated_at DESC
                          LIMIT 1 BY id
                      ) AS t
                      LEFT JOIN (
                          SELECT
                              trace_id,
                              sum(total_estimated_cost) AS total_estimated_cost,
                              sumMap(usage) AS usage
                          FROM spans final
                          WHERE workspace_id = :workspace_id
                          <if(has_target_projects)>AND project_id IN :target_project_ids<endif>
                          AND trace_id IN (SELECT trace_id FROM experiment_items_ids)
                          GROUP BY workspace_id, project_id, trace_id
                      ) s ON s.trace_id = t.id
                  ) AS tfs ON ei.trace_id = tfs.id
                  LEFT JOIN feedback_scores_per_trace AS fsp ON ei.trace_id = fsp.entity_id
                  LEFT JOIN comments_per_trace AS cp ON ei.trace_id = cp.entity_id
                  <endif>
              )  as final_result
              ORDER BY id DESC, last_updated_at DESC
              LIMIT :limit
              SETTINGS log_comment = '<log_comment>', output_format_json_named_tuples_as_objects = true
              ;
              """;

    private static final String DELETE = """
            DELETE FROM experiment_items
            WHERE id IN :ids
            AND workspace_id = :workspace_id
            SETTINGS log_comment = '<log_comment>'
            ;
            """;

    private static final String FIND_EXPERIMENT_SUMMARY_BY_DATASET_IDS = """
            SELECT
                e.dataset_id,
                count(distinct ei.experiment_id) as experiment_count,
                max(ei.last_updated_at) as most_recent_experiment_at
            FROM experiment_items ei
            JOIN experiments e ON ei.experiment_id = e.id AND e.workspace_id = ei.workspace_id
            WHERE e.dataset_id in :dataset_ids
            AND ei.workspace_id = :workspace_id
            GROUP BY
                e.dataset_id
            SETTINGS log_comment = '<log_comment>'
            ;
            """;

    private static final String DELETE_BY_EXPERIMENT_IDS = """
            DELETE FROM experiment_items
            WHERE experiment_id IN :experiment_ids
            AND workspace_id = :workspace_id
            SETTINGS log_comment = '<log_comment>'
            ;
            """;

    private static final String GET_EXPERIMENT_REFS_BY_TRACE_IDS = """
            SELECT ei.experiment_id, ei.trace_id
            FROM experiment_items AS ei FINAL
            INNER JOIN experiments AS ea FINAL
                ON ea.id = ei.experiment_id
                AND ea.workspace_id = ei.workspace_id
            WHERE ei.workspace_id = :workspace_id
            AND ei.trace_id IN :trace_ids
            AND ea.status IN :statuses
            SETTINGS log_comment = '<log_comment>'
            ;
            """;

    private static final String GET_EXPERIMENT_REFS_BY_ITEM_IDS = """
            SELECT ei.experiment_id, ei.trace_id
            FROM experiment_items AS ei FINAL
            INNER JOIN experiments AS ea FINAL
                ON ea.id = ei.experiment_id
                AND ea.workspace_id = ei.workspace_id
            WHERE ei.workspace_id = :workspace_id
            AND ei.id IN :item_ids
            AND ea.status IN :statuses
            SETTINGS log_comment = '<log_comment>'
            ;
            """;

    private static final String GET_EXPERIMENT_REFS_BY_SPAN_IDS = """
            SELECT ei.experiment_id, ei.trace_id
            FROM experiment_items AS ei FINAL
            INNER JOIN experiments AS ea FINAL
                ON ea.id = ei.experiment_id
                AND ea.workspace_id = ei.workspace_id
            WHERE ei.workspace_id = :workspace_id
            AND ei.trace_id IN (
                SELECT DISTINCT trace_id FROM spans FINAL
                WHERE id IN :span_ids AND workspace_id = :workspace_id
            )
            AND ea.status IN :statuses
            SETTINGS log_comment = '<log_comment>'
            ;
            """;

    private static final String FILTER_EXPERIMENT_IDS_BY_STATUS = """
            SELECT id
            FROM experiments FINAL
            WHERE workspace_id = :workspace_id
            AND id IN :experiment_ids
            AND status IN :statuses
            SETTINGS log_comment = '<log_comment>'
            ;
            """;

    private final @NonNull ConnectionFactory connectionFactory;
    private final @NonNull OpikConfiguration configuration;
    private final @NonNull ExperimentAggregatesDAO experimentAggregatesDAO;

    @WithSpan
    public Flux<ExperimentSummary> findExperimentSummaryByDatasetIds(Set<UUID> datasetIds) {

        if (datasetIds.isEmpty()) {
            return Flux.empty();
        }

        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> {
                    Statement statement = connection.createStatement(FIND_EXPERIMENT_SUMMARY_BY_DATASET_IDS);

                    statement.bind("dataset_ids", datasetIds.stream().map(UUID::toString).toArray(String[]::new));

                    return makeFluxContextAware(bindWorkspaceIdToFlux(statement));
                })
                .flatMap(result -> result.map((row, rowMetadata) -> new ExperimentSummary(
                        row.get("dataset_id", UUID.class),
                        row.get("experiment_count", Long.class),
                        row.get("most_recent_experiment_at", Instant.class))));
    }

    @WithSpan
    public Mono<Long> insert(@NonNull Set<ExperimentItem> experimentItems) {
        Preconditions.checkArgument(CollectionUtils.isNotEmpty(experimentItems),
                "Argument 'experimentItems' must not be empty");

        log.info("Inserting experiment items, count '{}'", experimentItems.size());

        if (experimentItems.isEmpty()) {
            return Mono.just(0L);
        }

        return Mono.from(connectionFactory.create())
                .flatMap(connection -> insert(experimentItems, connection));
    }

    private Mono<Long> insert(Collection<ExperimentItem> experimentItems, Connection connection) {

        return makeMonoContextAware((userName, workspaceId) -> {
            List<QueryItem> queryItems = getQueryItemPlaceHolder(experimentItems.size());

            var template = getSTWithLogComment(INSERT, "insert_experiment_items", workspaceId, experimentItems.size())
                    .add("items", queryItems);

            String sql = template.render();

            var statement = connection.createStatement(sql);

            statement.bind("workspace_id", workspaceId);

            int index = 0;
            for (ExperimentItem item : experimentItems) {
                statement.bind("id" + index, item.id());
                statement.bind("experiment_id" + index, item.experimentId());
                statement.bind("dataset_item_id" + index, item.datasetItemId());
                statement.bind("trace_id" + index, item.traceId());

                if (item.projectId() != null) {
                    statement.bind("project_id" + index, item.projectId().toString());
                } else {
                    statement.bindNull("project_id" + index, String.class);
                }

                statement.bind("created_by" + index, userName);
                statement.bind("last_updated_by" + index, userName);
                statement.bind("execution_policy" + index, ExecutionPolicyMapper.serialize(item.executionPolicy()));
                index++;
            }

            return Flux.from(statement.execute())
                    .flatMap(Result::getRowsUpdated)
                    .reduce(0L, Long::sum);
        });
    }

    @WithSpan
    public Mono<ExperimentItem> get(@NonNull UUID id) {
        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> get(id, connection))
                .flatMap(ExperimentItemMapper::mapToExperimentItem)
                .singleOrEmpty();
    }

    private Publisher<? extends Result> get(UUID id, Connection connection) {
        log.info("Getting experiment item by id '{}'", id);

        Statement statement = connection.createStatement(SELECT)
                .bind("id", id);

        return makeFluxContextAware(bindWorkspaceIdToFlux(statement));
    }

    public Flux<ExperimentItem> getItems(@NonNull Set<UUID> experimentIds,
            @NonNull ExperimentItemSearchCriteria criteria) {
        if (experimentIds.isEmpty()) {
            log.info("Getting experiment items by empty experimentIds, limit '{}', lastRetrievedId '{}'",
                    criteria.limit(), criteria.lastRetrievedId());
            return Flux.empty();
        }
        var aggregationCriteria = AggregationBranchCountsCriteria.builder()
                .experimentIds(experimentIds)
                .build();

        return Mono.zip(getAggregationBranchCounts(aggregationCriteria),
                getTargetProjectIds(experimentIds))
                .flatMapMany(tuple -> {
                    var counts = tuple.getT1();
                    var targetProjectIds = tuple.getT2();
                    return Mono.from(connectionFactory.create())
                            .flatMapMany(connection -> getItems(experimentIds, criteria, connection, counts,
                                    targetProjectIds))
                            .flatMap(ExperimentItemMapper::mapToExperimentItemFullContent);
                });
    }

    private Mono<AggregatedExperimentCounts> getAggregationBranchCounts(
            @NonNull AggregationBranchCountsCriteria criteria) {
        return experimentAggregatesDAO.getAggregationBranchCounts(criteria);
    }

    private Mono<List<UUID>> getTargetProjectIds(Set<UUID> experimentIds) {
        return Mono.from(connectionFactory.create())
                .flatMap(connection -> {
                    var template = TemplateUtils.newST(SELECT_TARGET_PROJECTS);
                    template.add("log_comment", "get_target_project_ids_experiment_items");

                    var statement = connection.createStatement(template.render())
                            .bind("experiment_ids", experimentIds.toArray(UUID[]::new));

                    return makeFluxContextAware(bindWorkspaceIdToFlux(statement))
                            .flatMap(result -> result.map((row, metadata) -> row.get("project_id", UUID.class)))
                            .collectList();
                });
    }

    private Publisher<? extends Result> getItems(
            Set<UUID> experimentIds, ExperimentItemSearchCriteria criteria, Connection connection,
            AggregatedExperimentCounts counts, List<UUID> targetProjectIds) {

        int limit = criteria.limit();
        UUID lastRetrievedId = criteria.lastRetrievedId();

        log.info("Getting experiment items by experimentIds count '{}', limit '{}', lastRetrievedId '{}'",
                experimentIds.size(), limit, lastRetrievedId);

        return makeFluxContextAware((userName, workspaceId) -> {
            var template = getSTWithLogComment(STREAM, "get_experiment_items_stream", workspaceId,
                    experimentIds.size());
            if (lastRetrievedId != null) {
                template.add("lastRetrievedId", lastRetrievedId);
            }
            template = ImageUtils.addTruncateToTemplate(template, criteria.truncate());
            template = template.add("truncationSize", configuration.getResponseFormatting().getTruncationSize());
            template.add("has_aggregated", counts.hasAggregated());
            template.add("has_raw", counts.hasRaw());
            if (CollectionUtils.isNotEmpty(targetProjectIds)) {
                template.add("has_target_projects", true);
            }
            var statement = connection.createStatement(template.render())
                    .bind("experiment_ids", experimentIds.toArray(UUID[]::new))
                    .bind("limit", limit)
                    .bind("workspace_id", workspaceId);
            if (lastRetrievedId != null) {
                statement.bind("lastRetrievedId", lastRetrievedId);
            }
            if (CollectionUtils.isNotEmpty(targetProjectIds)) {
                statement.bind("target_project_ids", targetProjectIds.toArray(UUID[]::new));
            }
            return Flux.from(statement.execute());
        });
    }

    @WithSpan
    public Mono<Long> delete(Set<UUID> ids) {
        Preconditions.checkArgument(CollectionUtils.isNotEmpty(ids),
                "Argument 'ids' must not be empty");

        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> delete(ids, connection))
                .flatMap(Result::getRowsUpdated)
                .reduce(0L, Long::sum);
    }

    private Publisher<? extends Result> delete(Set<UUID> ids, Connection connection) {
        log.info("Deleting experiment items, count '{}'", ids.size());

        Statement statement = connection.createStatement(DELETE)
                .bind("ids", ids.stream().map(UUID::toString).toArray(String[]::new));

        return makeFluxContextAware(bindWorkspaceIdToFlux(statement));
    }

    @WithSpan
    public Mono<Long> deleteByExperimentIds(Set<UUID> experimentIds) {

        Preconditions.checkArgument(CollectionUtils.isNotEmpty(experimentIds),
                "Argument 'experimentIds' must not be empty");

        log.info("Deleting experiment items by experiment ids, size '{}'", experimentIds.size());

        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> deleteByExperimentIds(experimentIds, connection))
                .flatMap(Result::getRowsUpdated)
                .reduce(0L, Long::sum)
                .doFinally(signalType -> {
                    if (signalType == SignalType.ON_COMPLETE) {
                        log.info("Deleted experiment items by experiment ids, size '{}'", experimentIds.size());
                    }
                });
    }

    private Flux<? extends Result> deleteByExperimentIds(Set<UUID> ids, Connection connection) {
        Statement statement = connection.createStatement(DELETE_BY_EXPERIMENT_IDS)
                .bind("experiment_ids", ids.toArray(UUID[]::new));

        return makeFluxContextAware(bindWorkspaceIdToFlux(statement));
    }

    @WithSpan
    public Flux<ExperimentTraceRef> getExperimentRefsByTraceIds(@NonNull Set<UUID> traceIds,
            @NonNull Set<ExperimentStatus> statuses) {
        return getExperimentRefsByIds(GET_EXPERIMENT_REFS_BY_TRACE_IDS, "trace_ids", traceIds, statuses);
    }

    @WithSpan
    public Flux<ExperimentTraceRef> getExperimentRefsByItemIds(@NonNull Set<UUID> itemIds,
            @NonNull Set<ExperimentStatus> statuses) {
        return getExperimentRefsByIds(GET_EXPERIMENT_REFS_BY_ITEM_IDS, "item_ids", itemIds, statuses);
    }

    @WithSpan
    public Flux<ExperimentTraceRef> getExperimentRefsBySpanIds(@NonNull Set<UUID> spanIds,
            @NonNull Set<ExperimentStatus> statuses) {
        return getExperimentRefsByIds(GET_EXPERIMENT_REFS_BY_SPAN_IDS, "span_ids", spanIds, statuses);
    }

    private Flux<ExperimentTraceRef> getExperimentRefsByIds(@NonNull String sql, @NonNull String idParamName,
            @NonNull Set<UUID> ids, @NonNull Set<ExperimentStatus> statuses) {
        if (ids.isEmpty() || statuses.isEmpty()) {
            return Flux.empty();
        }

        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> {
                    Statement statement = connection.createStatement(sql)
                            .bind(idParamName, ids.stream().map(UUID::toString).toArray(String[]::new))
                            .bind("statuses", statuses.stream().map(ExperimentStatus::getValue).toArray(String[]::new));

                    return makeFluxContextAware(bindWorkspaceIdToFlux(statement));
                })
                .flatMap(result -> result.map((row, rowMetadata) -> new ExperimentTraceRef(
                        row.get("experiment_id", UUID.class),
                        row.get("trace_id", UUID.class))));
    }

    @WithSpan
    public Flux<UUID> filterExperimentIdsByStatus(@NonNull Set<UUID> experimentIds,
            @NonNull Set<ExperimentStatus> statuses) {
        if (experimentIds.isEmpty() || statuses.isEmpty()) {
            return Flux.empty();
        }

        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> {
                    Statement statement = connection.createStatement(FILTER_EXPERIMENT_IDS_BY_STATUS)
                            .bind("experiment_ids",
                                    experimentIds.stream().map(UUID::toString).toArray(String[]::new))
                            .bind("statuses", statuses.stream().map(ExperimentStatus::getValue).toArray(String[]::new));

                    return makeFluxContextAware(bindWorkspaceIdToFlux(statement));
                })
                .flatMap(result -> result.map((row, rowMetadata) -> row.get("id", UUID.class)));
    }
}

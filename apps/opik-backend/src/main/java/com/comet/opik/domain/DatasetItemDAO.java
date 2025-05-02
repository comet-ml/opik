package com.comet.opik.domain;

import com.clickhouse.client.ClickHouseException;
import com.comet.opik.api.Column;
import com.comet.opik.api.DatasetItem;
import com.comet.opik.api.DatasetItemSearchCriteria;
import com.comet.opik.domain.filter.FilterQueryBuilder;
import com.comet.opik.domain.filter.FilterStrategy;
import com.comet.opik.infrastructure.db.TransactionTemplateAsync;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.inject.ImplementedBy;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import io.r2dbc.spi.Connection;
import io.r2dbc.spi.Result;
import io.r2dbc.spi.Statement;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.stringtemplate.v4.ST;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.comet.opik.api.DatasetItem.DatasetItemPage;
import static com.comet.opik.domain.AsyncContextUtils.bindWorkspaceIdToFlux;
import static com.comet.opik.domain.AsyncContextUtils.bindWorkspaceIdToMono;
import static com.comet.opik.infrastructure.instrumentation.InstrumentAsyncUtils.Segment;
import static com.comet.opik.infrastructure.instrumentation.InstrumentAsyncUtils.endSegment;
import static com.comet.opik.infrastructure.instrumentation.InstrumentAsyncUtils.startSegment;
import static com.comet.opik.utils.AsyncUtils.makeFluxContextAware;
import static com.comet.opik.utils.AsyncUtils.makeMonoContextAware;
import static com.comet.opik.utils.TemplateUtils.QueryItem;
import static com.comet.opik.utils.TemplateUtils.getQueryItemPlaceHolder;

@ImplementedBy(DatasetItemDAOImpl.class)
public interface DatasetItemDAO {
    Mono<Long> save(UUID datasetId, List<DatasetItem> batch);

    Mono<Long> delete(List<UUID> ids);

    Mono<DatasetItemPage> getItems(UUID datasetId, int page, int size, boolean truncate);

    Mono<DatasetItemPage> getItems(DatasetItemSearchCriteria datasetItemSearchCriteria, int page, int size);

    Mono<DatasetItem> get(UUID id);

    Flux<DatasetItem> getItems(UUID datasetId, int limit, UUID lastRetrievedId);

    Mono<List<WorkspaceAndResourceId>> getDatasetItemWorkspace(Set<UUID> datasetItemIds);

    Flux<DatasetItemSummary> findDatasetItemSummaryByDatasetIds(Set<UUID> datasetIds);

    Mono<List<Column>> getOutputColumns(UUID datasetId, Set<UUID> experimentIds);
}

@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Slf4j
class DatasetItemDAOImpl implements DatasetItemDAO {

    private static final String INSERT_DATASET_ITEM = """
                INSERT INTO dataset_items (
                    id,
                    dataset_id,
                    source,
                    trace_id,
                    span_id,
                    data,
                    created_at,
                    workspace_id,
                    created_by,
                    last_updated_by
                )
                VALUES
                    <items:{item |
                        (
                             :id<item.index>,
                             :datasetId<item.index>,
                             :source<item.index>,
                             :traceId<item.index>,
                             :spanId<item.index>,
                             :data<item.index>,
                             now64(9),
                             :workspace_id,
                             :createdBy<item.index>,
                             :lastUpdatedBy<item.index>
                         )
                         <if(item.hasNext)>
                            ,
                         <endif>
                    }>
                ;
            """;

    private static final String SELECT_DATASET_ITEM = """
                SELECT
                    *,
                    null AS experiment_items_array
                FROM dataset_items
                WHERE id = :id
                AND workspace_id = :workspace_id
                ORDER BY last_updated_at DESC
                LIMIT 1 BY id
                ;
            """;

    private static final String SELECT_DATASET_ITEMS_STREAM = """
                SELECT
                    *,
                    null AS experiment_items_array
                FROM dataset_items
                WHERE dataset_id = :datasetId
                AND workspace_id = :workspace_id
                <if(lastRetrievedId)>AND id \\< :lastRetrievedId <endif>
                ORDER BY id DESC, last_updated_at DESC
                LIMIT 1 BY id
                LIMIT :limit
                ;
            """;

    private static final String DELETE_DATASET_ITEM = """
                DELETE FROM dataset_items
                WHERE id IN :ids
                AND workspace_id = :workspace_id
                ;
            """;

    private static final String SELECT_DATASET_ITEMS = """
                SELECT
                    id,
                    dataset_id,
                    <if(truncate)> mapApply((k, v) -> (k, replaceRegexpAll(v, '<truncate>', '"[image]"')), data) as data <else> data <endif>,
                    trace_id,
                    span_id,
                    source,
                    created_at,
                    last_updated_at,
                    created_by,
                    last_updated_by,
                    null AS experiment_items_array
                FROM dataset_items
                WHERE dataset_id = :datasetId
                AND workspace_id = :workspace_id
                ORDER BY id DESC, last_updated_at DESC
                LIMIT 1 BY id
                LIMIT :limit OFFSET :offset
                ;
            """;

    private static final String SELECT_DATASET_ITEMS_COUNT = """
                SELECT
                    count(id) AS count,
                    arrayFold(
                        (acc, x) -> mapFromArrays(
                            arrayMap(key -> key, arrayDistinct(arrayConcat(mapKeys(acc), mapKeys(x)))),
                            arrayMap(key -> arrayDistinct(arrayConcat(acc[key], x[key])), arrayDistinct(arrayConcat(mapKeys(acc), mapKeys(x))))
                        ),
                        arrayDistinct(
                            arrayFlatten(
                                groupArray(
                                    arrayMap(key -> map(key, [toString(JSONType(data[key]))]), mapKeys(data))
                                )
                            )
                        ),
                        CAST(map(), 'Map(String, Array(String))')
                    ) AS columns
                FROM (
                    SELECT
                        id,
                        data
                    FROM dataset_items
                    WHERE dataset_id = :datasetId
                    AND workspace_id = :workspace_id
                    ORDER BY (workspace_id, dataset_id, source, trace_id, span_id, id) DESC, last_updated_at DESC
                    LIMIT 1 BY id
                ) AS lastRows
                ;
            """;

    private static final String SELECT_DATASET_ITEMS_COLUMNS_BY_DATASET_ID = """
                SELECT
                    arrayFold(
                        (acc, x) -> mapFromArrays(
                            arrayMap(key -> key, arrayDistinct(arrayConcat(mapKeys(acc), mapKeys(x)))),
                            arrayMap(key -> arrayDistinct(arrayConcat(acc[key], x[key])), arrayDistinct(arrayConcat(mapKeys(acc), mapKeys(x))))
                        ),
                        arrayDistinct(
                            arrayFlatten(
                                groupArray(
                                    arrayMap(key -> map(key, [toString(JSONType(data[key]))]), mapKeys(data))
                                )
                            )
                        ),
                        CAST(map(), 'Map(String, Array(String))')
                    ) AS columns
                FROM (
                    SELECT
                        id,
                        data
                    FROM dataset_items
                    WHERE dataset_id = :datasetId
                    AND workspace_id = :workspace_id
                    ORDER BY (workspace_id, dataset_id, source, trace_id, span_id, id) DESC, last_updated_at DESC
                    LIMIT 1 BY id
                )
                ;
            """;

    private static final String FIND_DATASET_ITEMS_SUMMARY_BY_DATASET_IDS = """
                        SELECT
                            dataset_id,
                            count(id) AS count
                        FROM (
                                 SELECT
                                     id,
                                     dataset_id
                                 FROM dataset_items
                                 WHERE dataset_id IN :dataset_ids
                                   AND workspace_id = :workspace_id
                                 ORDER BY (workspace_id, dataset_id, source, trace_id, span_id, id) DESC, last_updated_at DESC
                                 LIMIT 1 BY id
                                 ) AS lastRows
                        GROUP BY dataset_id
                        ;
            """;

    /**
     * Counts dataset items only if there's a matching experiment item.
     */
    private static final String SELECT_DATASET_ITEMS_WITH_EXPERIMENT_ITEMS_COUNT = """
                <if(feedback_scores_empty_filters)>
                WITH fsc AS (SELECT entity_id, COUNT(entity_id) AS feedback_scores_count
                    FROM (
                        SELECT *
                        FROM feedback_scores
                        WHERE entity_type = 'trace'
                        AND workspace_id = :workspace_id
                        ORDER BY (workspace_id, project_id, entity_type, entity_id, name) DESC, last_updated_at DESC
                        LIMIT 1 BY entity_id, name
                     )
                     GROUP BY entity_id
                     HAVING <feedback_scores_empty_filters>
                )
                <endif>
                SELECT
                   COUNT(DISTINCT di.id) AS count
                FROM (
                    SELECT
                        id
                    FROM dataset_items
                    WHERE dataset_id = :datasetId
                    AND workspace_id = :workspace_id
                    <if(dataset_item_filters)>
                    AND <dataset_item_filters>
                    <endif>
                    ORDER BY (workspace_id, dataset_id, source, trace_id, span_id, id) DESC, last_updated_at DESC
                    LIMIT 1 BY id
                ) AS di
                INNER JOIN (
                    SELECT
                        dataset_item_id,
                        trace_id
                    FROM experiment_items ei
                    <if(experiment_item_filters || feedback_scores_filters || feedback_scores_empty_filters)>
                    INNER JOIN (
                        SELECT
                            id
                        FROM traces
                        <if(feedback_scores_empty_filters)>
                            LEFT JOIN fsc ON fsc.entity_id = traces.id
                        <endif>
                        WHERE workspace_id = :workspace_id
                        <if(experiment_item_filters)>
                        AND <experiment_item_filters>
                        <endif>
                        <if(feedback_scores_filters)>
                        AND id in (
                            SELECT
                                entity_id
                            FROM (
                                SELECT *
                                FROM feedback_scores
                                WHERE entity_type = 'trace'
                                AND workspace_id = :workspace_id
                                ORDER BY (workspace_id, project_id, entity_type, entity_id, name) DESC, last_updated_at DESC
                                LIMIT 1 BY entity_id, name
                            )
                            GROUP BY entity_id
                            HAVING <feedback_scores_filters>
                        )
                        <endif>
                        <if(feedback_scores_empty_filters)>
                        AND fsc.feedback_scores_count = 0
                        <endif>
                        ORDER BY (workspace_id, project_id, id) DESC, last_updated_at DESC
                        LIMIT 1 BY id
                    ) AS tfs ON ei.trace_id = tfs.id
                    <endif>
                    WHERE experiment_id in :experimentIds
                    AND workspace_id = :workspace_id
                    ORDER BY (workspace_id, experiment_id, dataset_item_id, trace_id, id) DESC, last_updated_at DESC
                    LIMIT 1 BY id
                ) AS ei ON di.id = ei.dataset_item_id
                ;
            """;

    private static final String SELECT_DATASET_WORKSPACE_ITEMS = """
                SELECT
                    DISTINCT id, workspace_id
                FROM dataset_items
                WHERE id IN :datasetItemIds
                ;
            """;

    /**
     * Gets the following relationships:
     * - dataset_item - experiment_items -> 1:N
     * - experiment_item - trace -> 1:1
     * - trace - feedback_scores -> 1:N
     * And groups everything together resembling the following rough JSON structure:
     *  {
     *      "dataset_item" : {
     *          "id": "some_id",
     *          ...
     *          "experiment_items": [
     *            {
     *                "id": "some_id",
     *                "input": "trace_input_value",
     *                "output": "trace_output_value",
     *                "feedback_scores": [
     *                  {
     *                    "name": "some_name",
     *                    ...
     *                  },
     *                  ...
     *                ]
     *            },
     *            ...
     *          ]
     *          "
     *      }
     *  }
     */
    private static final String SELECT_DATASET_ITEMS_WITH_EXPERIMENT_ITEMS = """
            WITH dataset_items_final AS (
            	SELECT * FROM dataset_items
            	WHERE dataset_id = :datasetId
            	AND workspace_id = :workspace_id
            	<if(dataset_item_filters)>
                AND <dataset_item_filters>
                <endif>
            	ORDER BY id DESC, last_updated_at DESC
            	LIMIT 1 BY id
            ), experiment_items_scope as (
                SELECT
                    *
                FROM experiment_items
                WHERE workspace_id = :workspace_id
                AND experiment_id IN :experimentIds
                ORDER BY (workspace_id, experiment_id, dataset_item_id, trace_id, id) DESC, last_updated_at DESC
            	LIMIT 1 BY id
            ), feedback_scores_final AS (
            	SELECT
                	entity_id,
                    name,
                    category_name,
                    value,
                    reason,
                    source,
                    created_at,
                    last_updated_at,
                    created_by,
                    last_updated_by
                FROM feedback_scores
                WHERE workspace_id = :workspace_id
                AND entity_type = :entityType
                AND entity_id IN (SELECT trace_id FROM experiment_items_scope)
                ORDER BY (workspace_id, project_id, entity_type, entity_id, name) DESC, last_updated_at DESC
                LIMIT 1 BY entity_id, name
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
                AND entity_id IN (SELECT trace_id FROM experiment_items_scope)
                ORDER BY (workspace_id, project_id, entity_id, id) DESC, last_updated_at DESC
                LIMIT 1 BY id
            ),
            <if(feedback_scores_empty_filters)>
            fsc AS (SELECT entity_id, COUNT(entity_id) AS feedback_scores_count
                 FROM feedback_scores_final
                 GROUP BY entity_id
                 HAVING <feedback_scores_empty_filters>
            ),
            <endif>
            experiment_items_final AS (
            	SELECT
            		ei.*
            	FROM experiment_items_scope ei
            	WHERE workspace_id = :workspace_id
            	<if(experiment_item_filters || feedback_scores_filters || feedback_scores_empty_filters)>
                AND trace_id IN (
                    SELECT
                        id
                    FROM traces
                    <if(feedback_scores_empty_filters)>
                        LEFT JOIN fsc ON fsc.entity_id = traces.id
                    <endif>
                    WHERE workspace_id = :workspace_id
                    <if(experiment_item_filters)>
                    AND <experiment_item_filters>
                    <endif>
                    <if(feedback_scores_filters)>
                    AND id IN (
                        SELECT
                            entity_id
                        FROM feedback_scores_final
                        GROUP BY entity_id
                        HAVING <feedback_scores_filters>
                    )
                    <endif>
                    <if(feedback_scores_empty_filters)>
                    AND fsc.feedback_scores_count = 0
                    <endif>
                    ORDER BY (workspace_id, project_id, id) DESC, last_updated_at DESC
                    LIMIT 1 BY id
                )
                <endif>
            	ORDER BY id DESC, last_updated_at DESC
            )
            SELECT
                di.id AS id,
                di.dataset_id AS dataset_id,
                <if(truncate)> mapApply((k, v) -> (k, replaceRegexpAll(v, '<truncate>', '"[image]"')), di.data) as data <else> di.data <endif>,
                di.trace_id AS trace_id,
                di.span_id AS span_id,
                di.source AS source,
                di.created_at AS created_at,
                di.last_updated_at AS last_updated_at,
                di.created_by AS created_by,
                di.last_updated_by AS last_updated_by,
                groupArray(tuple(
                    ei.id,
                    ei.experiment_id,
                    ei.dataset_item_id,
                    ei.trace_id,
                    tfs.input,
                    tfs.output,
                    tfs.feedback_scores_array,
                    ei.created_at,
                    ei.last_updated_at,
                    ei.created_by,
                    ei.last_updated_by,
                    tfs.comments_array_agg,
                    tfs.duration,
                    tfs.total_estimated_cost,
                    tfs.usage
                )) AS experiment_items_array
            FROM dataset_items_final AS di
            INNER JOIN experiment_items_final AS ei ON di.id = ei.dataset_item_id
            LEFT JOIN (
                SELECT
                    t.id,
                    t.input,
                    t.output,
                    t.duration,
                    s.total_estimated_cost,
                    s.usage,
                    groupUniqArray(tuple(fs.*)) AS feedback_scores_array,
                    groupUniqArray(tuple(c.*)) AS comments_array_agg
                FROM (
                    SELECT
                        id,
                       if(end_time IS NOT NULL AND start_time IS NOT NULL
                                             AND notEquals(start_time, toDateTime64('1970-01-01 00:00:00.000', 9)),
                                         (dateDiff('microsecond', start_time, end_time) / 1000.0),
                                         NULL) AS duration,
                        <if(truncate)> replaceRegexpAll(input, '<truncate>', '"[image]"') as input <else> input <endif>,
                        <if(truncate)> replaceRegexpAll(output, '<truncate>', '"[image]"') as output <else> output <endif>
                    FROM traces
                    WHERE workspace_id = :workspace_id
                    AND id IN (SELECT trace_id FROM experiment_items_final)
                    ORDER BY (workspace_id, project_id, id) DESC, last_updated_at DESC
                    LIMIT 1 BY id
                ) AS t
                LEFT JOIN feedback_scores_final AS fs ON t.id = fs.entity_id
                LEFT JOIN comments_final AS c ON t.id = c.entity_id
                LEFT JOIN (
                    SELECT
                        trace_id,
                        SUM(total_estimated_cost) AS total_estimated_cost,
                        sumMap(usage) AS usage
                    FROM spans final
                    WHERE workspace_id = :workspace_id
                    AND trace_id IN (SELECT trace_id FROM experiment_items_scope)
                    GROUP BY workspace_id, project_id, trace_id
                ) s ON t.id = s.trace_id
                GROUP BY
                    t.id,
                    t.input,
                    t.output,
                    t.duration,
                    s.total_estimated_cost,
                    s.usage
            ) AS tfs ON ei.trace_id = tfs.id
            GROUP BY
                di.id,
                di.dataset_id,
                di.data,
                di.trace_id,
                di.span_id,
                di.source,
                di.created_at,
                di.last_updated_at,
                di.created_by,
                di.last_updated_by
            ORDER BY di.id DESC, di.last_updated_at DESC
            LIMIT :limit OFFSET :offset
            ;
            """;
    public static final String DATASET_ITEMS = "dataset_items";
    public static final String CLICKHOUSE = "Clickhouse";

    private static final String SELECT_DATASET_EXPERIMENT_ITEMS_COLUMNS_BY_DATASET_ID = """
                WITH dataset_item_final AS (
                    SELECT
                        id
                    FROM dataset_items
                    WHERE workspace_id = :workspace_id
                    AND dataset_id = :dataset_id
                    ORDER BY (workspace_id, dataset_id, source, trace_id, span_id, id) DESC, last_updated_at DESC
                    LIMIT 1 BY id
                ), experiment_items_final AS (
                    SELECT DISTINCT
                        ei.trace_id,
                        ei.dataset_item_id
                    FROM experiment_items ei
                    WHERE workspace_id = :workspace_id
                    AND ei.dataset_item_id IN (SELECT id FROM dataset_item_final)
                    <if(experiment_ids)> AND ei.experiment_id IN :experiment_ids <endif>
                    ORDER BY (workspace_id, experiment_id, dataset_item_id, trace_id, id) DESC, last_updated_at DESC
                    LIMIT 1 BY id
                )
                SELECT
                    arrayFold(
                        (acc, x) -> mapFromArrays(
                            arrayMap(key -> key, arrayDistinct(arrayConcat(mapKeys(acc), mapKeys(x)))),
                            arrayMap(
                                key -> arrayDistinct(arrayConcat(acc[key], x[key])),
                                arrayDistinct(arrayConcat(mapKeys(acc), mapKeys(x)))
                            )
                        ),
                        arrayDistinct(
                            arrayFlatten(
                                groupArray(
                                    arrayMap(
                                        key -> map(key, [toString(JSONType(JSONExtractRaw(output, key)))]),
                                        JSONExtractKeys(output)
                                    )
                                )
                            )
                        ),
                        CAST(map(), 'Map(String, Array(String))')
                    ) AS columns
                FROM dataset_item_final as di
                INNER JOIN experiment_items_final as ei ON ei.dataset_item_id = di.id
                INNER JOIN (
                    SELECT
                        id,
                        output
                    FROM traces final
                    WHERE workspace_id = :workspace_id
                    AND id IN (SELECT trace_id FROM experiment_items_final)
                ) as t ON t.id = ei.trace_id
                ;
            """;

    private final @NonNull TransactionTemplateAsync asyncTemplate;
    private final @NonNull FilterQueryBuilder filterQueryBuilder;

    @Override
    @WithSpan
    public Mono<Long> save(@NonNull UUID datasetId, @NonNull List<DatasetItem> items) {

        if (items.isEmpty()) {
            return Mono.empty();
        }

        return asyncTemplate.nonTransaction(connection -> mapAndInsert(
                datasetId, items, connection, INSERT_DATASET_ITEM));
    }

    private Mono<Long> mapAndInsert(
            UUID datasetId, List<DatasetItem> items, Connection connection, String sqlTemplate) {

        List<QueryItem> queryItems = getQueryItemPlaceHolder(items.size());

        var template = new ST(sqlTemplate)
                .add("items", queryItems);

        String sql = template.render();

        var statement = connection.createStatement(sql);

        return makeMonoContextAware((userName, workspaceId) -> {

            statement.bind("workspace_id", workspaceId);

            int i = 0;
            for (DatasetItem item : items) {
                Map<String, JsonNode> data = new HashMap<>(Optional.ofNullable(item.data()).orElse(Map.of()));

                statement.bind("id" + i, item.id());
                statement.bind("datasetId" + i, datasetId);
                statement.bind("source" + i, item.source().getValue());
                statement.bind("traceId" + i, DatasetItemResultMapper.getOrDefault(item.traceId()));
                statement.bind("spanId" + i, DatasetItemResultMapper.getOrDefault(item.spanId()));
                statement.bind("data" + i, DatasetItemResultMapper.getOrDefault(data));
                statement.bind("createdBy" + i, userName);
                statement.bind("lastUpdatedBy" + i, userName);
                i++;
            }

            Segment segment = startSegment(DATASET_ITEMS, CLICKHOUSE, "insert_dataset_items");

            return Flux.from(statement.execute())
                    .flatMap(Result::getRowsUpdated)
                    .reduce(0L, Long::sum)
                    .doFinally(signalType -> endSegment(segment));
        });
    }

    @Override
    @WithSpan
    public Mono<DatasetItem> get(@NonNull UUID id) {
        return asyncTemplate.nonTransaction(connection -> {

            Statement statement = connection.createStatement(SELECT_DATASET_ITEM)
                    .bind("id", id);

            Segment segment = startSegment(DATASET_ITEMS, CLICKHOUSE, "select_dataset_item");

            return makeFluxContextAware(bindWorkspaceIdToFlux(statement))
                    .doFinally(signalType -> endSegment(segment))
                    .flatMap(DatasetItemResultMapper::mapItem)
                    .singleOrEmpty();
        });
    }

    @Override
    @WithSpan
    public Flux<DatasetItem> getItems(@NonNull UUID datasetId, int limit, UUID lastRetrievedId) {
        log.info("Getting dataset items by datasetId '{}', limit '{}', lastRetrievedId '{}'",
                datasetId, limit, lastRetrievedId);

        ST template = new ST(SELECT_DATASET_ITEMS_STREAM);

        if (lastRetrievedId != null) {
            template.add("lastRetrievedId", lastRetrievedId);
        }

        return asyncTemplate.stream(connection -> {

            var statement = connection.createStatement(template.render())
                    .bind("datasetId", datasetId)
                    .bind("limit", limit);

            if (lastRetrievedId != null) {
                statement.bind("lastRetrievedId", lastRetrievedId);
            }

            Segment segment = startSegment(DATASET_ITEMS, CLICKHOUSE, "select_dataset_items_stream");

            return makeFluxContextAware(bindWorkspaceIdToFlux(statement))
                    .doFinally(signalType -> endSegment(segment))
                    .flatMap(DatasetItemResultMapper::mapItem);
        });
    }

    @Override
    @WithSpan
    public Mono<List<WorkspaceAndResourceId>> getDatasetItemWorkspace(@NonNull Set<UUID> datasetItemIds) {

        if (datasetItemIds.isEmpty()) {
            return Mono.just(List.of());
        }

        return asyncTemplate.nonTransaction(connection -> {

            var statement = connection.createStatement(SELECT_DATASET_WORKSPACE_ITEMS)
                    .bind("datasetItemIds", datasetItemIds.toArray(UUID[]::new));

            return Mono.from(statement.execute())
                    .flatMapMany(result -> result.map((row, rowMetadata) -> new WorkspaceAndResourceId(
                            row.get("workspace_id", String.class),
                            row.get("id", UUID.class))))
                    .collectList();
        });
    }

    @Override
    @WithSpan
    public Flux<DatasetItemSummary> findDatasetItemSummaryByDatasetIds(Set<UUID> datasetIds) {
        if (datasetIds.isEmpty()) {
            return Flux.empty();
        }

        return asyncTemplate.stream(connection -> {

            var statement = connection.createStatement(FIND_DATASET_ITEMS_SUMMARY_BY_DATASET_IDS)
                    .bind("dataset_ids", datasetIds);

            Segment segment = startSegment(DATASET_ITEMS, CLICKHOUSE, "find_dataset_item_summary_by_dataset_ids");

            return makeFluxContextAware(bindWorkspaceIdToFlux(statement))
                    .doFinally(signalType -> endSegment(segment))
                    .flatMap(result -> result.map((row, rowMetadata) -> DatasetItemSummary
                            .builder()
                            .datasetId(row.get("dataset_id", UUID.class))
                            .datasetItemsCount(row.get("count", Long.class))
                            .build()));
        });
    }

    @Override
    public Mono<List<Column>> getOutputColumns(@NonNull UUID datasetId, Set<UUID> experimentIds) {
        return asyncTemplate.nonTransaction(connection -> {

            ST template = new ST(SELECT_DATASET_EXPERIMENT_ITEMS_COLUMNS_BY_DATASET_ID);

            if (CollectionUtils.isNotEmpty(experimentIds)) {
                template.add("experiment_ids", experimentIds);
            }

            var statement = connection.createStatement(template.render())
                    .bind("dataset_id", datasetId);

            if (CollectionUtils.isNotEmpty(experimentIds)) {
                statement.bind("experiment_ids", experimentIds.toArray(UUID[]::new));
            }

            return makeMonoContextAware(bindWorkspaceIdToMono(statement))
                    .flatMap(result -> DatasetItemResultMapper.mapColumns(result, "output"))
                    .map(List::copyOf);
        });
    }

    @Override
    @WithSpan
    public Mono<Long> delete(@NonNull List<UUID> ids) {
        if (ids.isEmpty()) {
            return Mono.empty();
        }

        return asyncTemplate.nonTransaction(connection -> {

            Statement statement = connection.createStatement(DELETE_DATASET_ITEM);

            Segment segment = startSegment(DATASET_ITEMS, CLICKHOUSE, "delete_dataset_items");

            return bindAndDelete(ids, statement)
                    .flatMap(Result::getRowsUpdated)
                    .reduce(0L, Long::sum)
                    .doFinally(signalType -> endSegment(segment));
        });
    }

    private Flux<? extends Result> bindAndDelete(List<UUID> ids, Statement statement) {

        statement.bind("ids", ids.stream().map(UUID::toString).toArray(String[]::new));

        return makeFluxContextAware(bindWorkspaceIdToFlux(statement));
    }

    @Override
    @WithSpan
    public Mono<DatasetItemPage> getItems(@NonNull UUID datasetId, int page, int size, boolean truncate) {

        Segment segmentCount = startSegment(DATASET_ITEMS, CLICKHOUSE, "select_dataset_items_page_count");

        return makeMonoContextAware((userName, workspaceId) -> asyncTemplate.nonTransaction(connection -> Flux
                .from(connection.createStatement(SELECT_DATASET_ITEMS_COUNT)
                        .bind("datasetId", datasetId)
                        .bind("workspace_id", workspaceId)
                        .execute())
                .doFinally(signalType -> endSegment(segmentCount))
                .flatMap(results -> DatasetItemResultMapper.mapCountAndColumns(results, "data"))
                .reduce(DatasetItemResultMapper::groupResults)
                .flatMap(result -> {

                    Segment segment = startSegment(DATASET_ITEMS, CLICKHOUSE, "select_dataset_items_page");

                    long total = result.getKey();
                    Set<Column> columns = result.getValue();

                    ST template = ImageUtils.addTruncateToTemplate(new ST(SELECT_DATASET_ITEMS), truncate);

                    return Flux.from(connection.createStatement(template.render())
                            .bind("workspace_id", workspaceId)
                            .bind("datasetId", datasetId)
                            .bind("limit", size)
                            .bind("offset", (page - 1) * size)
                            .execute())
                            .flatMap(DatasetItemResultMapper::mapItem)
                            .collectList()
                            .flatMap(items -> Mono
                                    .just(new DatasetItemPage(items, page, items.size(), total, columns)))
                            .doFinally(signalType -> endSegment(segment));
                })));
    }

    private ST newFindTemplate(String query, DatasetItemSearchCriteria datasetItemSearchCriteria) {
        var template = new ST(query);

        Optional.ofNullable(datasetItemSearchCriteria.filters())
                .ifPresent(filters -> {
                    filterQueryBuilder.toAnalyticsDbFilters(filters, FilterStrategy.DATASET_ITEM)
                            .ifPresent(datasetItemFilters -> template.add("dataset_item_filters", datasetItemFilters));

                    filterQueryBuilder.toAnalyticsDbFilters(filters, FilterStrategy.EXPERIMENT_ITEM)
                            .ifPresent(experimentItemFilters -> template.add("experiment_item_filters",
                                    experimentItemFilters));

                    filterQueryBuilder.toAnalyticsDbFilters(filters, FilterStrategy.FEEDBACK_SCORES)
                            .ifPresent(scoresFilters -> template.add("feedback_scores_filters", scoresFilters));

                    filterQueryBuilder.toAnalyticsDbFilters(filters, FilterStrategy.FEEDBACK_SCORES_IS_EMPTY)
                            .ifPresent(feedbackScoreIsEmptyFilters -> template.add("feedback_scores_empty_filters",
                                    feedbackScoreIsEmptyFilters));
                });

        return template;
    }

    private void bindSearchCriteria(DatasetItemSearchCriteria datasetItemSearchCriteria, Statement statement) {
        Optional.ofNullable(datasetItemSearchCriteria.filters())
                .ifPresent(filters -> {
                    filterQueryBuilder.bind(statement, filters, FilterStrategy.DATASET_ITEM);
                    filterQueryBuilder.bind(statement, filters, FilterStrategy.EXPERIMENT_ITEM);
                    filterQueryBuilder.bind(statement, filters, FilterStrategy.FEEDBACK_SCORES);
                    filterQueryBuilder.bind(statement, filters, FilterStrategy.FEEDBACK_SCORES_IS_EMPTY);
                });
    }

    @Override
    @WithSpan
    public Mono<DatasetItemPage> getItems(
            @NonNull DatasetItemSearchCriteria datasetItemSearchCriteria, int page, int size) {
        log.info("Finding dataset items with experiment items by '{}', page '{}', size '{}'",
                datasetItemSearchCriteria, page, size);

        Segment segment = startSegment(DATASET_ITEMS, CLICKHOUSE,
                "select_dataset_items_experiments_filters_summary");

        Mono<Set<Column>> columnsMono = mapColumnsField(datasetItemSearchCriteria);
        Mono<Long> countMono = getCount(datasetItemSearchCriteria);

        return Mono.zip(countMono, columnsMono)
                .doFinally(signalType -> endSegment(segment))
                .flatMap(results -> asyncTemplate.nonTransaction(connection -> {

                    Segment segmentContent = startSegment(DATASET_ITEMS, CLICKHOUSE,
                            "select_dataset_items_experiments_filters");

                    ST selectTemplate = newFindTemplate(SELECT_DATASET_ITEMS_WITH_EXPERIMENT_ITEMS,
                            datasetItemSearchCriteria);
                    selectTemplate = ImageUtils.addTruncateToTemplate(selectTemplate,
                            datasetItemSearchCriteria.truncate());

                    var selectStatement = connection.createStatement(selectTemplate.render())
                            .bind("datasetId", datasetItemSearchCriteria.datasetId())
                            .bind("experimentIds", datasetItemSearchCriteria.experimentIds().toArray(UUID[]::new))
                            .bind("entityType", datasetItemSearchCriteria.entityType().getType())
                            .bind("limit", size)
                            .bind("offset", (page - 1) * size);

                    bindSearchCriteria(datasetItemSearchCriteria, selectStatement);

                    Long total = results.getT1();
                    Set<Column> columns = results.getT2();

                    return makeFluxContextAware(bindWorkspaceIdToFlux(selectStatement))
                            .doFinally(signalType -> endSegment(segmentContent))
                            .flatMap(DatasetItemResultMapper::mapItem)
                            .collectList()
                            .onErrorResume(e -> handleSqlError(e, List.of()))
                            .flatMap(
                                    items -> Mono.just(new DatasetItemPage(items, page, items.size(), total, columns)));
                }));
    }

    private Mono<Long> getCount(DatasetItemSearchCriteria datasetItemSearchCriteria) {
        Segment segment = startSegment(DATASET_ITEMS, CLICKHOUSE, "select_dataset_items_filters_columns");

        return asyncTemplate.nonTransaction(connection -> {

            ST countTemplate = newFindTemplate(SELECT_DATASET_ITEMS_WITH_EXPERIMENT_ITEMS_COUNT,
                    datasetItemSearchCriteria);

            var statement = connection.createStatement(countTemplate.render())
                    .bind("datasetId", datasetItemSearchCriteria.datasetId())
                    .bind("experimentIds", datasetItemSearchCriteria.experimentIds().toArray(UUID[]::new));

            bindSearchCriteria(datasetItemSearchCriteria, statement);

            return makeFluxContextAware(bindWorkspaceIdToFlux(statement))
                    .flatMap(DatasetItemResultMapper::mapCount)
                    .reduce(0L, Long::sum)
                    .onErrorResume(e -> handleSqlError(e, 0L))
                    .doFinally(signalType -> endSegment(segment));
        });
    }

    private Mono<Set<Column>> mapColumnsField(DatasetItemSearchCriteria datasetItemSearchCriteria) {
        Segment segment = startSegment(DATASET_ITEMS, CLICKHOUSE, "select_dataset_items_filters_columns");

        return asyncTemplate.nonTransaction(connection -> makeMonoContextAware(
                bindWorkspaceIdToMono(
                        connection.createStatement(SELECT_DATASET_ITEMS_COLUMNS_BY_DATASET_ID)
                                .bind("datasetId", datasetItemSearchCriteria.datasetId())))
                .flatMap(result -> DatasetItemResultMapper.mapColumns(result, "data")))
                .doFinally(signalType -> endSegment(segment));
    }

    private <T> Mono<T> handleSqlError(Throwable e, T defaultValue) {
        if (e instanceof ClickHouseException && e.getMessage().contains("Unable to parse JSONPath. (BAD_ARGUMENTS)")) {
            return Mono.just(defaultValue);
        }
        return Mono.error(e);
    }
}

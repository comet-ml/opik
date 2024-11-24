package com.comet.opik.domain;

import com.clickhouse.client.ClickHouseException;
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
import static com.comet.opik.api.DatasetItem.DatasetItemPage.Column;
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
                    input,
                    data,
                    expected_output,
                    metadata,
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
                             :input<item.index>,
                             :data<item.index>,
                             :expectedOutput<item.index>,
                             :metadata<item.index>,
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
                    input,
                    <if(truncate)> mapApply((k, v) -> (k, replaceRegexpAll(v, '<truncate>', '"[image]"')), data) as data <else> data <endif>,
                    expected_output,
                    metadata,
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
                    ORDER BY id DESC, last_updated_at DESC
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
                    ORDER BY id DESC, last_updated_at DESC
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
                                 ORDER BY id DESC, last_updated_at DESC
                                 LIMIT 1 BY id
                                 ) AS lastRows
                        GROUP BY dataset_id
                        ;
            """;

    /**
     * Counts dataset items only if there's a matching experiment item.
     */
    private static final String SELECT_DATASET_ITEMS_WITH_EXPERIMENT_ITEMS_COUNT = """
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
                    ORDER BY id DESC, last_updated_at DESC
                    LIMIT 1 BY id
                ) AS di
                INNER JOIN (
                    SELECT
                        dataset_item_id,
                        trace_id
                    FROM experiment_items ei
                    <if(experiment_item_filters || feedback_scores_filters)>
                    INNER JOIN (
                        SELECT
                            id
                        FROM traces
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
                                ORDER BY entity_id DESC, last_updated_at DESC
                                LIMIT 1 BY entity_id, name
                            )
                            GROUP BY entity_id
                            HAVING <feedback_scores_filters>
                        )
                        <endif>
                        ORDER BY id DESC, last_updated_at DESC
                        LIMIT 1 BY id
                    ) AS tfs ON ei.trace_id = tfs.id
                    <endif>
                    WHERE experiment_id in :experimentIds
                    AND workspace_id = :workspace_id
                    ORDER BY id DESC, last_updated_at DESC
                    LIMIT 1 BY id
                ) AS ei ON di.id = ei.dataset_item_id
                ;
            """;

    private static final String SELECT_DATASET_WORKSPACE_ITEMS = """
                SELECT
                    id, workspace_id
                FROM dataset_items
                WHERE id IN :datasetItemIds
                ORDER BY id DESC, last_updated_at DESC
                LIMIT 1 BY id
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
            SELECT
                di.id AS id,
                di.dataset_id AS dataset_id,
                di.input AS input,
                <if(truncate)> mapApply((k, v) -> (k, replaceRegexpAll(v, '<truncate>', '"[image]"')), di.data) as data <else> di.data <endif>,
                di.expected_output AS expected_output,
                di.metadata AS metadata,
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
                    ei.last_updated_by
                )) AS experiment_items_array
            FROM (
                SELECT
                    *
                FROM dataset_items
                WHERE dataset_id = :datasetId
                AND workspace_id = :workspace_id
                <if(dataset_item_filters)>
                AND <dataset_item_filters>
                <endif>
                ORDER BY id DESC, last_updated_at DESC
                LIMIT 1 BY id
            ) AS di
            INNER JOIN (
                SELECT
                    DISTINCT ei.*
                FROM experiment_items ei
                <if(experiment_item_filters || feedback_scores_filters)>
                INNER JOIN (
                    SELECT
                        id
                    FROM traces
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
                            ORDER BY entity_id DESC, last_updated_at DESC
                            LIMIT 1 BY entity_id, name
                        )
                        GROUP BY entity_id
                        HAVING <feedback_scores_filters>
                    )
                    <endif>
                    ORDER BY id DESC, last_updated_at DESC
                    LIMIT 1 BY id
                ) AS tfs ON ei.trace_id = tfs.id
                <endif>
                WHERE experiment_id in :experimentIds
                AND workspace_id = :workspace_id
                ORDER BY id DESC, last_updated_at DESC
                LIMIT 1 BY id
            ) AS ei ON di.id = ei.dataset_item_id
            LEFT JOIN (
                SELECT
                    t.id,
                    t.input,
                    t.output,
                    groupArray(tuple(
                        fs.entity_id,
                        fs.name,
                        fs.category_name,
                        fs.value,
                        fs.reason,
                        fs.source
                    )) AS feedback_scores_array
                FROM (
                    SELECT
                        id,
                        <if(truncate)> replaceRegexpAll(input, '<truncate>', '"[image]"') as input <else> input <endif>,
                        <if(truncate)> replaceRegexpAll(output, '<truncate>', '"[image]"') as output <else> output <endif>
                    FROM traces
                    WHERE workspace_id = :workspace_id
                    ORDER BY id DESC, last_updated_at DESC
                    LIMIT 1 BY id
                ) AS t
                LEFT JOIN (
                    SELECT
                        entity_id,
                        name,
                        category_name,
                        value,
                        reason,
                        source
                    FROM feedback_scores
                    WHERE entity_type = :entityType
                    AND workspace_id = :workspace_id
                    ORDER BY entity_id DESC, last_updated_at DESC
                    LIMIT 1 BY entity_id, name
                ) AS fs ON t.id = fs.entity_id
                GROUP BY
                    t.id,
                    t.input,
                    t.output
            ) AS tfs ON ei.trace_id = tfs.id
            GROUP BY
                di.id,
                di.dataset_id,
                di.input,
                di.data,
                di.expected_output,
                di.metadata,
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

                if (!data.containsKey("input") && item.input() != null) {
                    data.put("input", item.input());
                }

                if (!data.containsKey("expected_output") && item.expectedOutput() != null) {
                    data.put("expected_output", item.expectedOutput());
                }

                if (!data.containsKey("metadata") && item.metadata() != null) {
                    data.put("metadata", item.metadata());
                }

                statement.bind("id" + i, item.id());
                statement.bind("datasetId" + i, datasetId);
                statement.bind("source" + i, item.source().getValue());
                statement.bind("traceId" + i, DatasetItemResultMapper.getOrDefault(item.traceId()));
                statement.bind("spanId" + i, DatasetItemResultMapper.getOrDefault(item.spanId()));
                statement.bind("input" + i, DatasetItemResultMapper.getOrDefault(item.input()));
                statement.bind("data" + i, DatasetItemResultMapper.getOrDefault(data));
                statement.bind("expectedOutput" + i,
                        DatasetItemResultMapper.getOrDefault(item.expectedOutput()));
                statement.bind("metadata" + i, DatasetItemResultMapper.getOrDefault(item.metadata()));
                statement.bind("createdBy" + i, userName);
                statement.bind("lastUpdatedBy" + i, userName);
                i++;
            }

            Segment segment = startSegment("dataset_items", "Clickhouse", "insert_dataset_items");

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

            Segment segment = startSegment("dataset_items", "Clickhouse", "select_dataset_item");

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

            Segment segment = startSegment("dataset_items", "Clickhouse", "select_dataset_items_stream");

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

            Segment segment = startSegment("dataset_items", "Clickhouse", "find_dataset_item_summary_by_dataset_ids");

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
    @WithSpan
    public Mono<Long> delete(@NonNull List<UUID> ids) {
        if (ids.isEmpty()) {
            return Mono.empty();
        }

        return asyncTemplate.nonTransaction(connection -> {

            Statement statement = connection.createStatement(DELETE_DATASET_ITEM);

            Segment segment = startSegment("dataset_items", "Clickhouse", "delete_dataset_items");

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

        Segment segmentCount = startSegment("dataset_items", "Clickhouse", "select_dataset_items_page_count");

        return makeMonoContextAware((userName, workspaceId) -> asyncTemplate.nonTransaction(connection -> Flux
                .from(connection.createStatement(SELECT_DATASET_ITEMS_COUNT)
                        .bind("datasetId", datasetId)
                        .bind("workspace_id", workspaceId)
                        .execute())
                .doFinally(signalType -> endSegment(segmentCount))
                .flatMap(DatasetItemResultMapper::mapCountAndColumns)
                .reduce(DatasetItemResultMapper::groupResults)
                .flatMap(result -> {

                    Segment segment = startSegment("dataset_items", "Clickhouse", "select_dataset_items_page");

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
                });

        return template;
    }

    private void bindSearchCriteria(DatasetItemSearchCriteria datasetItemSearchCriteria, Statement statement) {
        Optional.ofNullable(datasetItemSearchCriteria.filters())
                .ifPresent(filters -> {
                    filterQueryBuilder.bind(statement, filters, FilterStrategy.DATASET_ITEM);
                    filterQueryBuilder.bind(statement, filters, FilterStrategy.EXPERIMENT_ITEM);
                    filterQueryBuilder.bind(statement, filters, FilterStrategy.FEEDBACK_SCORES);
                });
    }

    @Override
    @WithSpan
    public Mono<DatasetItemPage> getItems(
            @NonNull DatasetItemSearchCriteria datasetItemSearchCriteria, int page, int size) {
        log.info("Finding dataset items with experiment items by '{}', page '{}', size '{}'",
                datasetItemSearchCriteria, page, size);

        Segment segment = startSegment("dataset_items", "Clickhouse",
                "select_dataset_items_experiments_filters_summary");

        Mono<Set<Column>> columnsMono = mapColumnsField(datasetItemSearchCriteria);
        Mono<Long> countMono = getCount(datasetItemSearchCriteria);

        return Mono.zip(countMono, columnsMono)
                .doFinally(signalType -> endSegment(segment))
                .flatMap(results -> asyncTemplate.nonTransaction(connection -> {

                    Segment segmentContent = startSegment("dataset_items", "Clickhouse",
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
        Segment segment = startSegment("dataset_items", "Clickhouse", "select_dataset_items_filters_columns");

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
        Segment segment = startSegment("dataset_items", "Clickhouse", "select_dataset_items_filters_columns");

        return asyncTemplate.nonTransaction(connection -> makeMonoContextAware(
                bindWorkspaceIdToMono(
                        connection.createStatement(SELECT_DATASET_ITEMS_COLUMNS_BY_DATASET_ID)
                                .bind("datasetId", datasetItemSearchCriteria.datasetId())))
                .flatMap(DatasetItemResultMapper::mapColumns))
                .doFinally(signalType -> endSegment(segment));
    }

    private <T> Mono<T> handleSqlError(Throwable e, T defaultValue) {
        if (e instanceof ClickHouseException && e.getMessage().contains("Unable to parse JSONPath. (BAD_ARGUMENTS)")) {
            return Mono.just(defaultValue);
        }
        return Mono.error(e);
    }
}

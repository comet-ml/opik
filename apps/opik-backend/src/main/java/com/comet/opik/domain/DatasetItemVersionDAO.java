package com.comet.opik.domain;

import com.comet.opik.api.Column;
import com.comet.opik.api.DatasetItem;
import com.comet.opik.api.DatasetItem.DatasetItemPage;
import com.comet.opik.api.sorting.SortingFactoryDatasets;
import com.comet.opik.domain.filter.FilterQueryBuilder;
import com.comet.opik.domain.filter.FilterStrategy;
import com.comet.opik.domain.sorting.SortingQueryBuilder;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.db.TransactionTemplateAsync;
import com.comet.opik.utils.template.TemplateUtils;
import com.google.inject.ImplementedBy;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import io.r2dbc.spi.Result;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.stringtemplate.v4.ST;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.comet.opik.domain.AsyncContextUtils.bindWorkspaceIdToFlux;
import static com.comet.opik.infrastructure.instrumentation.InstrumentAsyncUtils.Segment;
import static com.comet.opik.infrastructure.instrumentation.InstrumentAsyncUtils.endSegment;
import static com.comet.opik.infrastructure.instrumentation.InstrumentAsyncUtils.startSegment;
import static com.comet.opik.utils.AsyncUtils.makeFluxContextAware;
import static com.comet.opik.utils.AsyncUtils.makeMonoContextAware;

@ImplementedBy(DatasetItemVersionDAOImpl.class)
public interface DatasetItemVersionDAO {
    Mono<Long> makeSnapshot(UUID datasetId, UUID versionId, List<UUID> uuids);

    Mono<DatasetItemPage> getItems(DatasetItemSearchCriteria searchCriteria, int page, int size, UUID versionId);

    Flux<DatasetItemIdAndHash> getItemIdsAndHashes(UUID datasetId, UUID versionId);

    Mono<ItemsHash> getVersionItemsHashAgg(UUID datasetId, UUID versionId);

    Flux<DatasetItem> getItems(UUID datasetId, UUID versionId, int limit, UUID lastRetrievedId);

    Mono<DatasetItemPage> getItemsWithExperimentItems(DatasetItemSearchCriteria searchCriteria, int page, int size);
}

@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Slf4j
class DatasetItemVersionDAOImpl implements DatasetItemVersionDAO {

    private static final String DATASET_ITEM_VERSIONS = "dataset_item_versions";
    private static final String CLICKHOUSE = "Clickhouse";

    private static final String INSERT_SNAPSHOT = """
            INSERT INTO dataset_item_versions (
                id,
                dataset_item_id,
                dataset_id,
                dataset_version_id,
                data,
                metadata,
                source,
                trace_id,
                span_id,
                tags,
                item_created_at,
                item_last_updated_at,
                item_created_by,
                item_last_updated_by,
                created_at,
                last_updated_at,
                created_by,
                last_updated_by,
                workspace_id
            )
            SELECT
                arrayElement(:uuids, row_number() OVER ()) as id,
                dataset_items.id as dataset_item_id,
                dataset_id,
                :versionId as dataset_version_id,
                data,
                metadata,
                source,
                trace_id,
                span_id,
                tags,
                created_at as item_created_at,
                last_updated_at as item_last_updated_at,
                created_by as item_created_by,
                last_updated_by as item_last_updated_by,
                now64(9) as created_at,
                now64(9) as last_updated_at,
                :user_name as created_by,
                :user_name as last_updated_by,
                workspace_id
            FROM dataset_items
            WHERE dataset_id = :datasetId
            AND workspace_id = :workspace_id
            ORDER BY (workspace_id, dataset_id, source, trace_id, span_id, dataset_items.id) DESC, last_updated_at DESC
            LIMIT 1 BY dataset_items.id
            """;

    private static final String SELECT_ITEM_IDS_AND_HASHES = """
            SELECT
                dataset_item_id,
                data_hash
            FROM dataset_item_versions
            WHERE dataset_id = :datasetId
            AND dataset_version_id = :versionId
            AND workspace_id = :workspace_id
            ORDER BY (workspace_id, dataset_id, dataset_version_id, id) DESC, last_updated_at DESC
            LIMIT 1 BY id
            """;

    private static final String SELECT_DATASET_ITEM_VERSIONS = """
            SELECT
                id,
                dataset_item_id,
                dataset_id,
                data,
                trace_id,
                span_id,
                source,
                tags,
                item_created_at as created_at,
                item_last_updated_at as last_updated_at,
                item_created_by as created_by,
                item_last_updated_by as last_updated_by,
                null AS experiment_items_array
            FROM dataset_item_versions
            WHERE dataset_id = :datasetId
            AND dataset_version_id = :versionId
            AND workspace_id = :workspace_id
            ORDER BY (workspace_id, dataset_id, dataset_version_id, id) DESC, last_updated_at DESC
            LIMIT :limit OFFSET :offset
            """;

    private static final String SELECT_DATASET_ITEM_VERSIONS_COUNT = """
            SELECT count(DISTINCT id) as count
            FROM dataset_item_versions
            WHERE dataset_id = :datasetId
            AND dataset_version_id = :versionId
            AND workspace_id = :workspace_id
            """;

    private static final String SELECT_DATASET_ITEM_VERSIONS_STREAM = """
            SELECT
                id,
                dataset_item_id,
                dataset_id,
                data,
                trace_id,
                span_id,
                source,
                tags,
                item_created_at as created_at,
                item_last_updated_at as last_updated_at,
                item_created_by as created_by,
                item_last_updated_by as last_updated_by,
                null AS experiment_items_array
            FROM dataset_item_versions
            WHERE dataset_id = :datasetId
            AND dataset_version_id = :versionId
            AND workspace_id = :workspace_id
            <if(lastRetrievedId)>AND dataset_item_id \\< :lastRetrievedId <endif>
            ORDER BY dataset_item_id DESC, last_updated_at DESC
            LIMIT 1 BY dataset_item_id
            LIMIT :limit
            """;

    private static final String SELECT_VERSION_ITEMS_HASH = """
            SELECT
                groupBitXor(xxHash64(dataset_item_id)) as id_hash,
                groupBitXor(data_hash) as data_hash
            FROM (
                SELECT data_hash, id, dataset_item_id
                FROM dataset_item_versions
                WHERE dataset_id = :datasetId
                AND dataset_version_id = :versionId
                AND workspace_id = :workspace_id
                ORDER BY (workspace_id, dataset_id, dataset_version_id, id) DESC, last_updated_at DESC
                LIMIT 1 BY id
            )
            """;

    /**
     * Query to fetch dataset items from versions with their associated experiment items.
     * This query is used when experiments are linked to specific dataset versions.
     */
    private static final String SELECT_DATASET_ITEM_VERSIONS_WITH_EXPERIMENT_ITEMS = """
            WITH experiments_with_versions AS (
                SELECT
                    id AS experiment_id,
                    dataset_version_id
                FROM experiments
                WHERE workspace_id = :workspace_id
                AND id IN :experimentIds
                ORDER BY (workspace_id, dataset_id, id) DESC, last_updated_at DESC
            	LIMIT 1 BY id
            ), experiment_items_scope as (
                SELECT
                    ei.id,
                    ei.experiment_id,
                    ei.dataset_item_id,
                    ei.trace_id,
                    ei.workspace_id,
                    ei.created_at,
                    ei.last_updated_at,
                    ei.created_by,
                    ei.last_updated_by,
                    e.dataset_version_id
                FROM experiment_items ei
                INNER JOIN experiments_with_versions e ON ei.experiment_id = e.experiment_id
                WHERE ei.workspace_id = :workspace_id
                AND ei.experiment_id IN :experimentIds
                ORDER BY (ei.workspace_id, ei.experiment_id, ei.dataset_item_id, ei.trace_id, ei.id) DESC, ei.last_updated_at DESC
            	LIMIT 1 BY ei.id
            ), dataset_items_final AS (
            	SELECT
            	    div.dataset_item_id AS id,
            	    div.dataset_id,
            	    div.data,
            	    div.metadata,
            	    div.source,
            	    div.trace_id,
            	    div.span_id,
            	    div.tags,
            	    div.item_created_at AS created_at,
            	    div.item_last_updated_at AS last_updated_at,
            	    div.item_created_by AS created_by,
            	    div.item_last_updated_by AS last_updated_by
            	FROM dataset_item_versions div
            	INNER JOIN (
            	    SELECT DISTINCT
            	        ei.dataset_item_id,
            	        ei.dataset_version_id
            	    FROM experiment_items_scope ei
            	) ei_with_version ON div.dataset_item_id = ei_with_version.dataset_item_id
            	    AND div.dataset_version_id = ei_with_version.dataset_version_id
            	WHERE div.workspace_id = :workspace_id
            	ORDER BY div.dataset_item_id DESC, div.last_updated_at DESC
            	LIMIT 1 BY div.dataset_item_id
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
                WHERE entity_type = :entityType
                  AND workspace_id = :workspace_id
                  AND entity_id IN (SELECT trace_id FROM experiment_items_scope)
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
                WHERE entity_type = :entityType
                  AND workspace_id = :workspace_id
                  AND entity_id IN (SELECT trace_id FROM experiment_items_scope)
            ),
            feedback_scores_with_ranking AS (
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
            ),
            feedback_scores_combined AS (
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
                ei.dataset_item_id AS id,
                :datasetId AS dataset_id,
                <if(truncate)> mapApply((k, v) -> (k, substring(replaceRegexpAll(v, '<truncate>', '"[image]"'), 1, <truncationSize>)), COALESCE(di.data, map())) <else> COALESCE(di.data, map()) <endif> AS data_final,
                COALESCE(di.data, map()) AS data,
                di.trace_id AS trace_id,
                di.span_id AS span_id,
                di.source AS source,
                di.tags AS tags,
                di.created_at AS created_at,
                di.last_updated_at AS last_updated_at,
                di.created_by AS created_by,
                di.last_updated_by AS last_updated_by,
                argMax(tfs.duration, ei.created_at) AS duration,
                argMax(tfs.total_estimated_cost, ei.created_at) AS total_estimated_cost,
                argMax(tfs.usage, ei.created_at) AS usage,
                argMax(tfs.feedback_scores, ei.created_at) AS feedback_scores,
                argMax(tfs.input, ei.created_at) AS input,
                argMax(tfs.output, ei.created_at) AS output,
                argMax(tfs.metadata, ei.created_at) AS metadata,
                argMax(tfs.visibility_mode, ei.created_at) AS visibility_mode,
                argMax(tfs.comments_array_agg, ei.created_at) AS comments,
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
                    tfs.usage,
                    tfs.visibility_mode,
                    tfs.metadata
                )) AS experiment_items_array
            FROM experiment_items_final AS ei
            INNER JOIN dataset_items_final AS di ON di.id = ei.dataset_item_id
            LEFT JOIN (
                SELECT
                    t.id,
                    t.input,
                    t.output,
                    t.metadata,
                    t.duration,
                    t.visibility_mode,
                    s.total_estimated_cost,
                    s.usage,
                    groupUniqArray(tuple(
                        fs.entity_id,
                        fs.name,
                        fs.category_name,
                        fs.value,
                        fs.reason,
                        fs.source,
                        fs.created_at,
                        fs.last_updated_at,
                        fs.created_by,
                        fs.last_updated_by,
                        fs.value_by_author
                    )) AS feedback_scores_array,
                    mapFromArrays(
                        groupArray(fs.name),
                        groupArray(fs.value)
                    ) AS feedback_scores,
                    groupUniqArray(tuple(c.*)) AS comments_array_agg
                FROM (
                    SELECT
                        id,
                       if(end_time IS NOT NULL AND start_time IS NOT NULL
                                             AND notEquals(start_time, toDateTime64('1970-01-01 00:00:00.000', 9)),
                                         (dateDiff('microsecond', start_time, end_time) / 1000.0),
                                         NULL) AS duration,
                        <if(truncate)> substring(replaceRegexpAll(input, '<truncate>', '"[image]"'), 1, <truncationSize>) as input <else> input <endif>,
                        <if(truncate)> substring(replaceRegexpAll(output, '<truncate>', '"[image]"'), 1, <truncationSize>) as output <else> output <endif>,
                        metadata,
                        visibility_mode
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
                GROUP BY workspace_id, trace_id
                ) s ON t.id = s.trace_id
                GROUP BY
                    t.id,
                    t.input,
                    t.output,
                    t.metadata,
                    t.duration,
                    t.visibility_mode,
                    s.total_estimated_cost,
                    s.usage
            ) AS tfs ON ei.trace_id = tfs.id
            GROUP BY
                ei.dataset_item_id,
                :datasetId,
                COALESCE(di.data, map()),
                di.trace_id,
                di.span_id,
                di.source,
                di.tags,
                di.created_at,
                di.last_updated_at,
                di.created_by,
                di.last_updated_by
            <if(dataset_item_filters || search_filter)>
            HAVING 1=1
            <if(dataset_item_filters)>
            AND <dataset_item_filters>
            <endif>
            <if(search_filter)>
            AND <search_filter>
            <endif>
            <endif>
            <if(sort_fields)>
            ORDER BY <sort_fields>
            <else>
            ORDER BY id DESC, last_updated_at DESC
            <endif>
            LIMIT :limit OFFSET :offset
            ;
            """;

    private final @NonNull TransactionTemplateAsync asyncTemplate;
    private final @NonNull FilterQueryBuilder filterQueryBuilder;
    private final @NonNull SortingQueryBuilder sortingQueryBuilder;
    private final @NonNull SortingFactoryDatasets sortingFactory;
    private final @NonNull OpikConfiguration config;

    @Override
    @WithSpan
    public Mono<Long> makeSnapshot(@NonNull UUID datasetId, @NonNull UUID versionId, @NonNull List<UUID> uuids) {
        log.info("Creating snapshot for dataset '{}', version '{}' using '{}' pre-generated UUIDs",
                datasetId, versionId, uuids.size());

        return asyncTemplate.nonTransaction(connection -> {
            // Convert UUIDs to String array for ClickHouse binding
            String[] uuidStrings = uuids.stream()
                    .map(UUID::toString)
                    .toArray(String[]::new);

            var statement = connection.createStatement(INSERT_SNAPSHOT)
                    .bind("datasetId", datasetId)
                    .bind("versionId", versionId)
                    .bind("uuids", uuidStrings);

            Segment segment = startSegment(DATASET_ITEM_VERSIONS, CLICKHOUSE, "create_version_snapshot");

            return makeMonoContextAware((userName, workspaceId) -> {
                statement.bind("workspace_id", workspaceId);
                statement.bind("user_name", userName);
                log.debug("Creating snapshot: datasetId='{}', versionId='{}', workspaceId='{}', userName='{}'",
                        datasetId, versionId, workspaceId, userName);

                return Flux.from(statement.execute())
                        .flatMap(Result::getRowsUpdated)
                        .reduce(0L, Long::sum)
                        .doOnSuccess(insertedCount -> log.info(
                                "Snapshot created: '{}' rows inserted for version '{}'",
                                insertedCount, versionId))
                        .doFinally(signalType -> endSegment(segment));
            });
        });
    }

    @Override
    @WithSpan
    public Flux<DatasetItemIdAndHash> getItemIdsAndHashes(@NonNull UUID datasetId, @NonNull UUID versionId) {
        log.debug("Getting item IDs and hashes for dataset '{}', version '{}'", datasetId, versionId);

        return asyncTemplate.stream(connection -> {
            var statement = connection.createStatement(SELECT_ITEM_IDS_AND_HASHES)
                    .bind("datasetId", datasetId)
                    .bind("versionId", versionId);

            Segment segment = startSegment(DATASET_ITEM_VERSIONS, CLICKHOUSE, "get_version_item_ids_and_hashes");

            return makeFluxContextAware(bindWorkspaceIdToFlux(statement))
                    .doFinally(signalType -> endSegment(segment))
                    .flatMap(result -> result.map((row, metadata) -> {
                        var datasetItemId = UUID.fromString(row.get("dataset_item_id", String.class));
                        var hash = row.get("data_hash", Long.class);
                        log.debug("Retrieved versioned item: dataset_item_id='{}', hash='{}'", datasetItemId, hash);
                        return DatasetItemIdAndHash.builder()
                                .itemId(datasetItemId)
                                .dataHash(hash)
                                .build();
                    }))
                    .collectList()
                    .doOnSuccess(items -> log.info("Retrieved '{}' item IDs and hashes for version '{}'", items.size(),
                            versionId))
                    .flatMapMany(Flux::fromIterable);
        });
    }

    @Override
    @WithSpan
    public Mono<DatasetItemPage> getItems(@NonNull DatasetItemSearchCriteria criteria, int page, int size,
            @NonNull UUID versionId) {

        // If experiment IDs are present, use the experiment items query
        if (CollectionUtils.isNotEmpty(criteria.experimentIds())) {
            log.info(
                    "Finding versioned dataset items with experiment items for dataset '{}', version '{}', experiments '{}', page '{}', size '{}'",
                    criteria.datasetId(), versionId, criteria.experimentIds(), page, size);
            return getItemsWithExperimentItems(criteria, page, size);
        }

        // Otherwise, use the regular versioned items query
        return Mono.zip(
                getCount(criteria.datasetId(), versionId),
                Mono.just(Set.<Column>of())).flatMap(tuple -> {
                    Long total = tuple.getT1();
                    Set<Column> columns = tuple.getT2();

                    return asyncTemplate.nonTransaction(connection -> {
                        var statement = connection.createStatement(SELECT_DATASET_ITEM_VERSIONS)
                                .bind("datasetId", criteria.datasetId().toString())
                                .bind("versionId", versionId.toString())
                                .bind("limit", size)
                                .bind("offset", (page - 1) * size);

                        Segment segment = startSegment(DATASET_ITEM_VERSIONS, CLICKHOUSE,
                                "select_dataset_item_versions");

                        return makeFluxContextAware(bindWorkspaceIdToFlux(statement))
                                .doFinally(signalType -> endSegment(segment))
                                .flatMap(DatasetItemResultMapper::mapItem)
                                .collectList()
                                .map(items -> new DatasetItemPage(items, page, items.size(), total, columns, null,
                                        false, null));
                    });
                });
    }

    private Mono<Long> getCount(UUID datasetId, UUID versionId) {
        return asyncTemplate.nonTransaction(connection -> {
            var statement = connection.createStatement(SELECT_DATASET_ITEM_VERSIONS_COUNT)
                    .bind("datasetId", datasetId.toString())
                    .bind("versionId", versionId.toString());

            Segment segment = startSegment(DATASET_ITEM_VERSIONS, CLICKHOUSE, "count_dataset_item_versions");

            return makeFluxContextAware(bindWorkspaceIdToFlux(statement))
                    .doFinally(signalType -> endSegment(segment))
                    .flatMap(result -> result.map((row, meta) -> row.get("count", Long.class)))
                    .reduce(0L, Long::sum);
        });
    }

    @Override
    @WithSpan
    public Mono<ItemsHash> getVersionItemsHashAgg(@NonNull UUID datasetId, @NonNull UUID versionId) {
        log.debug("Computing hash for version items of dataset: '{}', version: '{}'", datasetId, versionId);

        Segment segment = startSegment(DATASET_ITEM_VERSIONS, CLICKHOUSE, "get_version_items_hash_agg");

        return asyncTemplate.nonTransaction(connection -> {
            var statement = connection.createStatement(SELECT_VERSION_ITEMS_HASH)
                    .bind("datasetId", datasetId)
                    .bind("versionId", versionId);

            return makeFluxContextAware(bindWorkspaceIdToFlux(statement))
                    .doFinally(signalType -> endSegment(segment))
                    .flatMap(result -> result.map((row, metadata) -> {
                        long idHash = row.get("id_hash", Long.class);
                        long dataHash = row.get("data_hash", Long.class);
                        return ItemsHash.builder().idHash(idHash).dataHash(dataHash).build();
                    }))
                    .singleOrEmpty()
                    .defaultIfEmpty(ItemsHash.builder().idHash(0L).dataHash(0L).build());
        });
    }

    @Override
    @WithSpan
    public Flux<DatasetItem> getItems(@NonNull UUID datasetId, @NonNull UUID versionId, int limit,
            UUID lastRetrievedId) {
        log.info(
                "Streaming dataset items from version: datasetId='{}', versionId='{}', limit='{}', lastRetrievedId='{}'",
                datasetId, versionId, limit, lastRetrievedId);

        var template = TemplateUtils.newST(SELECT_DATASET_ITEM_VERSIONS_STREAM);

        if (lastRetrievedId != null) {
            template.add("lastRetrievedId", lastRetrievedId);
        }

        return asyncTemplate.stream(connection -> {
            var statement = connection.createStatement(template.render())
                    .bind("datasetId", datasetId)
                    .bind("versionId", versionId)
                    .bind("limit", limit);

            if (lastRetrievedId != null) {
                statement.bind("lastRetrievedId", lastRetrievedId);
            }

            Segment segment = startSegment(DATASET_ITEM_VERSIONS, CLICKHOUSE, "stream_dataset_item_versions");

            return makeFluxContextAware(bindWorkspaceIdToFlux(statement))
                    .doFinally(signalType -> endSegment(segment))
                    .flatMap(DatasetItemResultMapper::mapItem)
                    .doOnComplete(() -> log.info(
                            "Completed streaming dataset items from version: datasetId='{}', versionId='{}'",
                            datasetId, versionId));
        });
    }

    @Override
    @WithSpan
    public Mono<DatasetItemPage> getItemsWithExperimentItems(
            @NonNull DatasetItemSearchCriteria searchCriteria, int page, int size) {
        log.info(
                "Getting versioned dataset items with experiment items for dataset '{}', experiments '{}', page '{}', size '{}'",
                searchCriteria.datasetId(), searchCriteria.experimentIds(), page, size);

        Segment segment = startSegment(DATASET_ITEM_VERSIONS, CLICKHOUSE,
                "select_dataset_item_versions_with_experiment_items");

        Mono<Set<Column>> columnsMono = Mono.just(Set.of()); // Columns not needed for versioned items
        Mono<Long> countMono = getCountWithExperimentItems(searchCriteria);

        return Mono.zip(countMono, columnsMono)
                .doFinally(signalType -> endSegment(segment))
                .flatMap(results -> asyncTemplate.nonTransaction(connection -> {
                    Segment segmentContent = startSegment(DATASET_ITEM_VERSIONS, CLICKHOUSE,
                            "select_dataset_item_versions_with_experiment_items_content");

                    ST selectTemplate = newFindTemplate(SELECT_DATASET_ITEM_VERSIONS_WITH_EXPERIMENT_ITEMS,
                            searchCriteria);

                    // Add truncation if needed
                    selectTemplate = ImageUtils.addTruncateToTemplate(selectTemplate, searchCriteria.truncate());
                    selectTemplate = selectTemplate.add("truncationSize",
                            config.getResponseFormatting().getTruncationSize());

                    // Add sorting if present
                    final ST finalTemplate = selectTemplate;
                    Optional.ofNullable(sortingQueryBuilder.toOrderBySql(searchCriteria.sortingFields(),
                            filterQueryBuilder.buildDatasetItemFieldMapping(searchCriteria.sortingFields())))
                            .ifPresent(sortingQuery -> finalTemplate.add("sort_fields", sortingQuery));

                    var hasDynamicKeys = searchCriteria.sortingFields() != null
                            && sortingQueryBuilder.hasDynamicKeys(searchCriteria.sortingFields());

                    var selectStatement = connection.createStatement(finalTemplate.render())
                            .bind("datasetId", searchCriteria.datasetId())
                            .bind("experimentIds", searchCriteria.experimentIds().toArray(UUID[]::new))
                            .bind("entityType", searchCriteria.entityType().getType())
                            .bind("limit", size)
                            .bind("offset", (page - 1) * size);

                    // Bind dynamic sorting keys if present
                    if (hasDynamicKeys) {
                        selectStatement = sortingQueryBuilder.bindDynamicKeys(selectStatement,
                                searchCriteria.sortingFields());
                    }

                    bindSearchCriteria(searchCriteria, selectStatement);

                    Long total = results.getT1();
                    Set<Column> columns = results.getT2();

                    return makeFluxContextAware(bindWorkspaceIdToFlux(selectStatement))
                            .doFinally(signalType -> endSegment(segmentContent))
                            .flatMap(DatasetItemResultMapper::mapItem)
                            .collectList()
                            .map(items -> new DatasetItemPage(items, page, items.size(), total, columns,
                                    sortingFactory.getSortableFields(), false, null));
                }));
    }

    private ST newFindTemplate(String query, DatasetItemSearchCriteria searchCriteria) {
        var template = TemplateUtils.newST(query);

        Optional.ofNullable(searchCriteria.filters())
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

        Optional.ofNullable(searchCriteria.search())
                .filter(s -> !s.isBlank())
                .ifPresent(searchText -> template.add("search_filter",
                        filterQueryBuilder.buildDatasetItemSearchFilter(searchText)));

        return template;
    }

    private void bindSearchCriteria(DatasetItemSearchCriteria searchCriteria, io.r2dbc.spi.Statement statement) {
        Optional.ofNullable(searchCriteria.filters())
                .ifPresent(filters -> {
                    filterQueryBuilder.bind(statement, filters, FilterStrategy.DATASET_ITEM);
                    filterQueryBuilder.bind(statement, filters, FilterStrategy.EXPERIMENT_ITEM);
                    filterQueryBuilder.bind(statement, filters, FilterStrategy.FEEDBACK_SCORES);
                    filterQueryBuilder.bind(statement, filters, FilterStrategy.FEEDBACK_SCORES_IS_EMPTY);
                });

        Optional.ofNullable(searchCriteria.search())
                .filter(s -> !s.isBlank())
                .ifPresent(searchText -> filterQueryBuilder.bindSearchTerms(statement, searchText));
    }

    private Mono<Long> getCountWithExperimentItems(DatasetItemSearchCriteria searchCriteria) {
        // For versioned items with experiments, we need a count query
        // For now, return 0 as a placeholder - we can implement proper counting later if needed
        return Mono.just(0L);
    }
}

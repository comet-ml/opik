package com.comet.opik.domain;

import com.comet.opik.api.Column;
import com.comet.opik.api.DatasetItem;
import com.comet.opik.api.DatasetItem.DatasetItemPage;
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
import org.stringtemplate.v4.ST;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
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

    Flux<com.comet.opik.api.DatasetItem> getVersionItems(UUID datasetId, UUID versionId, int limit,
            UUID lastRetrievedId);

    Mono<ItemsHash> getVersionItemsHashAgg(UUID datasetId, UUID versionId);
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

    private static final String SELECT_DATASET_ITEMS_STREAM_VERSION = """
            SELECT
                *,
                null AS experiment_items_array
            FROM dataset_item_versions
            WHERE dataset_id = :datasetId
            AND dataset_version_id = :versionId
            AND workspace_id = :workspace_id
            <if(lastRetrievedId)>AND id \\< :lastRetrievedId <endif>
            ORDER BY id DESC, last_updated_at DESC
            LIMIT 1 BY id
            LIMIT :limit
            ;
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
                ORDER BY id
                LIMIT 1 BY id
            )
            """;

    private final @NonNull TransactionTemplateAsync asyncTemplate;

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
                                        null));
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
    public Flux<DatasetItem> getVersionItems(@NonNull UUID datasetId, @NonNull UUID versionId, int limit,
            UUID lastRetrievedId) {
        log.info("Getting versioned dataset items by datasetId '{}', versionId '{}', limit '{}', lastRetrievedId '{}'",
                datasetId, versionId, limit, lastRetrievedId);

        ST template = TemplateUtils.newST(SELECT_DATASET_ITEMS_STREAM_VERSION);

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

            Segment segment = startSegment(DATASET_ITEM_VERSIONS, CLICKHOUSE, "select_version_items_stream");

            return makeFluxContextAware(bindWorkspaceIdToFlux(statement))
                    .doFinally(signalType -> endSegment(segment))
                    .flatMap(DatasetItemResultMapper::mapItem);
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
                        Long idHash = row.get("id_hash", Long.class);
                        Long dataHash = row.get("data_hash", Long.class);
                        return new ItemsHash(
                                idHash != null ? idHash : 0L,
                                dataHash != null ? dataHash : 0L);
                    }))
                    .singleOrEmpty()
                    .defaultIfEmpty(new ItemsHash(0L, 0L));
        });
    }
}

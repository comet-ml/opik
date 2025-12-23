package com.comet.opik.domain;

import com.comet.opik.api.Column;
import com.comet.opik.api.DatasetItem.DatasetItemPage;
import com.comet.opik.api.filter.DatasetItemFilter;
import com.comet.opik.domain.filter.FilterQueryBuilder;
import com.comet.opik.domain.filter.FilterStrategy;
import com.comet.opik.infrastructure.auth.RequestContext;
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

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.comet.opik.domain.AsyncContextUtils.bindWorkspaceIdToFlux;
import static com.comet.opik.infrastructure.DatabaseUtils.generateUuidPool;
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

    /**
     * Copies items from a source version to a new target version directly within dataset_item_versions.
     * Each copied item gets a new UUIDv7 but retains the same dataset_item_id.
     * <p>
     * Optionally excludes items matching filters (items matching filters will NOT be copied).
     * If excludeFilters is null or empty, all items are copied.
     *
     * @param datasetId the dataset ID
     * @param sourceVersionId the source version to copy from
     * @param targetVersionId the new version ID to copy to
     * @param excludeFilters optional filters to exclude items (null or empty = copy all)
     * @param uuids pre-generated UUIDv7 pool for the new item IDs (should be at least 2x expected item count)
     * @return the number of items copied
     */
    Mono<Long> copyVersionItems(UUID datasetId, UUID sourceVersionId, UUID targetVersionId,
            List<DatasetItemFilter> excludeFilters, List<UUID> uuids);

    /**
     * Applies delta changes (add, edit, delete) from a base version to create a new version.
     * <p>
     * This operation:
     * <ul>
     *   <li>Copies items from baseVersion that are NOT in deletedIds and NOT in editedItems</li>
     *   <li>Inserts editedItems (with updated data but same datasetItemId)</li>
     *   <li>Inserts addedItems (with new datasetItemIds)</li>
     * </ul>
     *
     * @param datasetId the dataset ID
     * @param baseVersionId the base version to apply changes from
     * @param newVersionId the new version ID to create
     * @param addedItems new items to add
     * @param editedItems existing items with updated data
     * @param deletedIds item IDs (datasetItemId) to exclude from the new version
     * @param baseVersionItemCount the item count in the base version (for UUID pool sizing)
     * @return the total number of items in the new version
     */
    Mono<Long> applyDelta(UUID datasetId, UUID baseVersionId, UUID newVersionId,
            List<VersionedDatasetItem> addedItems, List<VersionedDatasetItem> editedItems, Set<UUID> deletedIds,
            int baseVersionItemCount);

    /**
     * Inserts items directly into a new version without copying from any base version.
     *
     * @param datasetId the dataset ID
     * @param versionId the version ID to insert into
     * @param items the items to insert
     * @param workspaceId the workspace ID
     * @param userName the user name
     * @return the number of items inserted
     */
    Mono<Long> insertItems(UUID datasetId, UUID versionId, List<VersionedDatasetItem> items,
            String workspaceId, String userName);

    /**
     * Gets the dataset ID for a given dataset_item_id from the versioned table.
     * This looks up the most recent version of an item to find its dataset.
     *
     * @param datasetItemId the stable item ID (dataset_item_id)
     * @return Mono emitting the dataset ID, or empty if not found
     */
    Mono<UUID> getDatasetIdByItemId(UUID datasetItemId);

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

    // Copy items from source version to target version
    // Optionally excludes items matching filters (when exclude_filters is set)
    private static final String COPY_VERSION_ITEMS = """
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
                src.dataset_item_id,
                src.dataset_id,
                :targetVersionId as dataset_version_id,
                src.data,
                src.metadata,
                src.source,
                src.trace_id,
                src.span_id,
                src.tags,
                src.item_created_at,
                src.item_last_updated_at,
                src.item_created_by,
                src.item_last_updated_by,
                now64(9) as created_at,
                now64(9) as last_updated_at,
                :user_name as created_by,
                :user_name as last_updated_by,
                src.workspace_id
            FROM (
                SELECT *
                FROM dataset_item_versions
                WHERE dataset_id = :datasetId
                AND dataset_version_id = :sourceVersionId
                AND workspace_id = :workspace_id
                <if(exclude_filters)>
                AND NOT (<exclude_filters>)
                <endif>
                ORDER BY (workspace_id, dataset_id, dataset_version_id, id) DESC, last_updated_at DESC
                LIMIT 1 BY id
            ) AS src
            """;

    private static final String SELECT_DATASET_ID_BY_ITEM_ID = """
            SELECT
                dataset_id
            FROM dataset_item_versions
            WHERE dataset_item_id = :datasetItemId
            AND workspace_id = :workspace_id
            ORDER BY (workspace_id, dataset_id, dataset_version_id, id) DESC, last_updated_at DESC
            LIMIT 1
            """;

    private final @NonNull TransactionTemplateAsync asyncTemplate;
    private final @NonNull IdGenerator idGenerator;

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
                                .map(items -> new DatasetItemPage(items, page, items.size(), total, columns, null));
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
    public Mono<Long> copyVersionItems(@NonNull UUID datasetId, @NonNull UUID sourceVersionId,
            @NonNull UUID targetVersionId, List<DatasetItemFilter> excludeFilters, @NonNull List<UUID> uuids) {

        log.info(
                "Copying items from version '{}' to version '{}' for dataset '{}', excludeFilters='{}', uuidPoolSize='{}'",
                sourceVersionId, targetVersionId, datasetId,
                excludeFilters != null ? excludeFilters.size() : 0, uuids.size());

        return Mono.deferContextual(ctx -> {
            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);
            String userName = ctx.get(RequestContext.USER_NAME);

            ST template = TemplateUtils.newST(COPY_VERSION_ITEMS);

            // Add filter conditions if provided
            if (excludeFilters != null && !excludeFilters.isEmpty()) {
                Optional<String> filterClause = FilterQueryBuilder.toAnalyticsDbFilters(excludeFilters,
                        FilterStrategy.DATASET_ITEM);
                filterClause.ifPresent(filters -> template.add("exclude_filters", filters));
            }

            String query = template.render();

            // Convert UUIDs to String array for ClickHouse binding
            String[] uuidStrings = uuids.stream()
                    .map(UUID::toString)
                    .toArray(String[]::new);

            return asyncTemplate.nonTransaction(connection -> {
                var statement = connection.createStatement(query)
                        .bind("datasetId", datasetId.toString())
                        .bind("sourceVersionId", sourceVersionId.toString())
                        .bind("targetVersionId", targetVersionId.toString())
                        .bind("uuids", uuidStrings)
                        .bind("workspace_id", workspaceId)
                        .bind("user_name", userName);

                // Bind filter parameters if provided
                if (excludeFilters != null && !excludeFilters.isEmpty()) {
                    FilterQueryBuilder.bind(statement, excludeFilters, FilterStrategy.DATASET_ITEM);
                }

                Segment segment = startSegment(DATASET_ITEM_VERSIONS, CLICKHOUSE, "copy_version_items");

                return Flux.from(statement.execute())
                        .flatMap(Result::getRowsUpdated)
                        .reduce(0L, Long::sum)
                        .doOnSuccess(copiedCount -> log.info(
                                "Copied '{}' items from version '{}' to version '{}' for dataset '{}'",
                                copiedCount, sourceVersionId, targetVersionId, datasetId))
                        .doFinally(signalType -> endSegment(segment));
            });
        });
    }

    @Override
    @WithSpan
    public Mono<Long> applyDelta(@NonNull UUID datasetId, @NonNull UUID baseVersionId,
            @NonNull UUID newVersionId, @NonNull List<VersionedDatasetItem> addedItems,
            @NonNull List<VersionedDatasetItem> editedItems, @NonNull Set<UUID> deletedIds,
            int baseVersionItemCount) {

        log.info("Applying delta for dataset '{}': baseVersion='{}', newVersion='{}', " +
                "added='{}', edited='{}', deleted='{}', baseItemCount='{}'",
                datasetId, baseVersionId, newVersionId, addedItems.size(), editedItems.size(),
                deletedIds.size(), baseVersionItemCount);

        // Collect all item IDs that are being edited (so we don't copy them from base)
        Set<UUID> editedItemIds = editedItems.stream()
                .map(VersionedDatasetItem::datasetItemId)
                .collect(Collectors.toSet());

        // Combine deleted and edited IDs for exclusion when copying
        Set<UUID> excludedIds = new HashSet<>(deletedIds);
        excludedIds.addAll(editedItemIds);

        // Calculate expected unchanged items and generate UUID pool
        int expectedUnchangedCount = Math.max(0, baseVersionItemCount - excludedIds.size());
        List<UUID> unchangedUuids = generateUuidPool(idGenerator, expectedUnchangedCount);

        return Mono.deferContextual(ctx -> {
            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);
            String userName = ctx.get(RequestContext.USER_NAME);

            // Step 1: Copy unchanged items from base version (excluding deleted and edited)
            Mono<Long> copyUnchanged = copyUnchangedItems(datasetId, baseVersionId, newVersionId,
                    excludedIds, unchangedUuids, workspaceId, userName);

            // Step 2: Insert edited items
            Mono<Long> insertEdited = insertItems(datasetId, newVersionId, editedItems, workspaceId, userName);

            // Step 3: Insert added items
            Mono<Long> insertAdded = insertItems(datasetId, newVersionId, addedItems, workspaceId, userName);

            // Execute all operations and sum the results
            return copyUnchanged
                    .zipWith(insertEdited, Long::sum)
                    .zipWith(insertAdded, Long::sum)
                    .doOnSuccess(total -> log.info("Applied delta for dataset '{}': total items in new version '{}'",
                            datasetId, total));
        });
    }

    private Mono<Long> copyUnchangedItems(UUID datasetId, UUID baseVersionId, UUID newVersionId,
            Set<UUID> excludedIds, List<UUID> uuids, String workspaceId, String userName) {

        if (excludedIds.isEmpty()) {
            // Simple copy - no exclusions needed
            return copyVersionItems(datasetId, baseVersionId, newVersionId, null, uuids)
                    .contextWrite(ctx -> ctx
                            .put(RequestContext.WORKSPACE_ID, workspaceId)
                            .put(RequestContext.USER_NAME, userName));
        }

        // Uses pre-generated UUIDv7 pool for time-ordered IDs
        String copyWithExclusionsQuery = """
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
                    arrayElement(:uuids, row_number() OVER ()) as new_id,
                    src.dataset_item_id,
                    src.dataset_id,
                    :newVersionId as dataset_version_id,
                    src.data,
                    src.metadata,
                    src.source,
                    src.trace_id,
                    src.span_id,
                    src.tags,
                    src.item_created_at,
                    src.item_last_updated_at,
                    src.item_created_by,
                    src.item_last_updated_by,
                    now64(9) as created_at,
                    now64(9) as last_updated_at,
                    :user_name as created_by,
                    :user_name as last_updated_by,
                    src.workspace_id
                FROM dataset_item_versions AS src
                WHERE src.dataset_id = :datasetId
                AND src.dataset_version_id = :baseVersionId
                AND src.workspace_id = :workspace_id
                AND src.dataset_item_id NOT IN (:excludedIds)
                ORDER BY (src.workspace_id, src.dataset_id, src.dataset_version_id, src.id) DESC, src.last_updated_at DESC
                LIMIT 1 BY src.id
                """;

        return asyncTemplate.nonTransaction(connection -> {
            String[] excludedIdStrings = excludedIds.stream()
                    .map(UUID::toString)
                    .toArray(String[]::new);

            String[] uuidStrings = uuids.stream()
                    .map(UUID::toString)
                    .toArray(String[]::new);

            var statement = connection.createStatement(copyWithExclusionsQuery)
                    .bind("datasetId", datasetId.toString())
                    .bind("baseVersionId", baseVersionId.toString())
                    .bind("newVersionId", newVersionId.toString())
                    .bind("excludedIds", excludedIdStrings)
                    .bind("uuids", uuidStrings)
                    .bind("workspace_id", workspaceId)
                    .bind("user_name", userName);

            Segment segment = startSegment(DATASET_ITEM_VERSIONS, CLICKHOUSE, "copy_unchanged_items");

            return Flux.from(statement.execute())
                    .flatMap(Result::getRowsUpdated)
                    .reduce(0L, Long::sum)
                    .doOnSuccess(count -> log.debug("Copied '{}' unchanged items", count))
                    .doFinally(signalType -> endSegment(segment));
        });
    }

    @Override
    @WithSpan
    public Mono<Long> insertItems(@NonNull UUID datasetId, @NonNull UUID newVersionId,
            @NonNull List<VersionedDatasetItem> items, @NonNull String workspaceId, @NonNull String userName) {

        if (items.isEmpty()) {
            return Mono.just(0L);
        }

        String insertQuery = """
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
                ) VALUES (
                    :id,
                    :dataset_item_id,
                    :dataset_id,
                    :dataset_version_id,
                    :data,
                    :metadata,
                    :source,
                    :trace_id,
                    :span_id,
                    :tags,
                    :item_created_at,
                    :item_last_updated_at,
                    :item_created_by,
                    :item_last_updated_by,
                    now64(9),
                    now64(9),
                    :created_by,
                    :last_updated_by,
                    :workspace_id
                )
                """;

        // Note: ClickHouse with async inserts returns 0 immediately before commit.
        // We return the count of items we're inserting instead of relying on getRowsUpdated.
        long itemCount = items.size();

        return asyncTemplate.nonTransaction(connection -> {
            Segment segment = startSegment(DATASET_ITEM_VERSIONS, CLICKHOUSE, "insert_delta_items");

            return Flux.fromIterable(items)
                    .flatMap(item -> {
                        UUID rowId = UUID.randomUUID();
                        Map<String, String> dataAsStrings = DatasetItemResultMapper.getOrDefault(item.data());
                        var statement = connection.createStatement(insertQuery)
                                .bind("id", rowId.toString())
                                .bind("dataset_item_id", item.datasetItemId().toString())
                                .bind("dataset_id", datasetId.toString())
                                .bind("dataset_version_id", newVersionId.toString())
                                .bind("data", dataAsStrings)
                                .bind("metadata", "")
                                .bind("source", item.source() != null ? item.source().getValue() : "sdk")
                                .bind("trace_id", DatasetItemResultMapper.getOrDefault(item.traceId()))
                                .bind("span_id", DatasetItemResultMapper.getOrDefault(item.spanId()))
                                .bind("tags", item.tags() != null ? item.tags().toArray(new String[0]) : new String[0])
                                .bind("item_created_at", formatTimestamp(item.createdAt()))
                                .bind("item_last_updated_at", formatTimestamp(item.lastUpdatedAt()))
                                .bind("item_created_by", item.createdBy() != null ? item.createdBy() : userName)
                                .bind("item_last_updated_by",
                                        item.lastUpdatedBy() != null ? item.lastUpdatedBy() : userName)
                                .bind("created_by", userName)
                                .bind("last_updated_by", userName)
                                .bind("workspace_id", workspaceId);

                        return Flux.from(statement.execute())
                                .flatMap(Result::getRowsUpdated)
                                .reduce(0L, Long::sum)
                                .doOnError(e -> log.error("Insert failed for item datasetItemId='{}': {}",
                                        item.datasetItemId(), e.getMessage()));
                    })
                    .collectList()
                    .map(results -> itemCount) // Return item count instead of sum of results
                    .doOnSuccess(count -> log.debug("Inserted '{}' items", count))
                    .doOnError(e -> log.error("Insert items failed: {}", e.getMessage()))
                    .doFinally(signalType -> endSegment(segment));
        });
    }

    /**
     * Formats an Instant for ClickHouse DateTime64(9, 'UTC').
     * ClickHouse doesn't accept the 'Z' suffix from ISO-8601 format.
     */
    private static String formatTimestamp(Instant timestamp) {
        if (timestamp == null) {
            return Instant.now().toString().replace("Z", "");
        }
        return timestamp.toString().replace("Z", "");
    }

    @Override
    @WithSpan
    public Mono<UUID> getDatasetIdByItemId(@NonNull UUID datasetItemId) {
        log.debug("Looking up dataset ID for item '{}'", datasetItemId);

        return asyncTemplate.nonTransaction(connection -> {
            var statement = connection.createStatement(SELECT_DATASET_ID_BY_ITEM_ID)
                    .bind("datasetItemId", datasetItemId.toString());

            Segment segment = startSegment(DATASET_ITEM_VERSIONS, CLICKHOUSE, "get_dataset_id_by_item_id");

            return makeMonoContextAware((userName, workspaceId) -> {
                statement.bind("workspace_id", workspaceId);

                return Flux.from(statement.execute())
                        .flatMap(result -> result
                                .map((row, rowMetadata) -> UUID.fromString(row.get("dataset_id", String.class))))
                        .next()
                        .doOnSuccess(datasetId -> {
                            if (datasetId != null) {
                                log.debug("Found dataset '{}' for item '{}'", datasetId, datasetItemId);
                            } else {
                                log.debug("No dataset found for item '{}'", datasetItemId);
                            }
                        })
                        .doFinally(signalType -> endSegment(segment));
            });
        });
    }

}

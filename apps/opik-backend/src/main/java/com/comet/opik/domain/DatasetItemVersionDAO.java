package com.comet.opik.domain;

import com.comet.opik.api.Column;
import com.comet.opik.api.DatasetItem;
import com.comet.opik.api.DatasetItem.DatasetItemPage;
import com.comet.opik.api.DatasetItemUpdate;
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
     *   <li>Inserts editedItems (with updated data but same stable ID)</li>
     *   <li>Inserts addedItems (with new stable IDs)</li>
     * </ul>
     * <p>
     * For items passed to this method:
     * - Use {@code draftItemId} field as the stable ID (maintained across versions)
     * - The {@code id} field is ignored (row IDs are generated internally)
     *
     * @param datasetId the dataset ID
     * @param baseVersionId the base version to apply changes from
     * @param newVersionId the new version ID to create
     * @param addedItems new items to add
     * @param editedItems existing items with updated data
     * @param deletedIds stable item IDs to exclude from the new version
     * @param baseVersionItemCount the item count in the base version (for UUID pool sizing)
     * @return the total number of items in the new version
     */
    Mono<Long> applyDelta(UUID datasetId, UUID baseVersionId, UUID newVersionId,
            List<DatasetItem> addedItems, List<DatasetItem> editedItems, Set<UUID> deletedIds,
            int baseVersionItemCount);

    /**
     * Applies batch updates to items from a base version, creating updated copies in a new version.
     * This is an efficient database-side operation using INSERT ... SELECT with conditional updates.
     * <p>
     * Only non-null fields in the update are applied. Items not in the itemIds set are not updated.
     *
     * @param datasetId the dataset ID
     * @param baseVersionId the base version to copy from
     * @param newVersionId the new version to insert into
     * @param itemIds the stable item IDs (draftItemId) to update
     * @param update the update to apply
     * @param mergeTags whether to merge tags or replace them
     * @param uuids pre-generated UUIDv7 pool for new row IDs
     * @return the number of items updated
     */
    Mono<Long> batchUpdateItems(UUID datasetId, UUID baseVersionId, UUID newVersionId,
            Set<UUID> itemIds, DatasetItemUpdate update, Boolean mergeTags, List<UUID> uuids);

    /**
     * Inserts items directly into a new version without copying from any base version.
     * <p>
     * For items passed to this method:
     * - Use {@code draftItemId} field as the stable ID (maintained across versions)
     * - The {@code id} field is ignored (row IDs are generated internally)
     *
     * @param datasetId the dataset ID
     * @param versionId the version ID to insert into
     * @param items the items to insert
     * @param workspaceId the workspace ID
     * @param userName the user name
     * @return the number of items inserted
     */
    Mono<Long> insertItems(UUID datasetId, UUID versionId, List<DatasetItem> items,
            String workspaceId, String userName);

    /**
     * Resolves which dataset contains the given item by looking across all versions.
     * This is used for initial lookup when only the item ID is known.
     *
     * Note: This method queries across versions to find which dataset contains the item.
     * It's only used for dataset resolution - actual data retrieval should use version-specific methods.
     *
     * @param datasetItemId the stable item ID (dataset_item_id)
     * @return Mono emitting the dataset ID, or empty if item not found
     */
    Mono<UUID> resolveDatasetIdFromItemId(UUID datasetItemId);

    /**
     * Gets an item by its dataset_item_id from a specific version.
     *
     * @param datasetId the dataset ID
     * @param versionId the version ID to retrieve the item from
     * @param datasetItemId the stable item ID (dataset_item_id)
     * @return Mono emitting the DatasetItem, or empty if not found
     */
    Mono<DatasetItem> getItemByDatasetItemId(UUID datasetId, UUID versionId, UUID datasetItemId);

    /**
     * Gets multiple items by their dataset_item_ids from a specific version in a single query.
     * This is the batch version of getItemByDatasetItemId to avoid N+1 queries.
     *
     * @param datasetId the dataset ID
     * @param versionId the version ID to retrieve items from
     * @param datasetItemIds the stable item IDs (dataset_item_id values)
     * @return Flux emitting DatasetItems for all found items
     */
    Flux<DatasetItem> getItemsByDatasetItemIds(UUID datasetId, UUID versionId, Set<UUID> datasetItemIds);

    /**
     * Maps row IDs (id field) to their corresponding stable item IDs (dataset_item_id).
     * This is used when the frontend sends row IDs but we need stable IDs for operations.
     *
     * @param rowIds the row IDs (id field values)
     * @return Flux emitting mappings of row ID to dataset_item_id
     */
    Flux<DatasetItemIdMapping> mapRowIdsToDatasetItemIds(Set<UUID> rowIds);

    /**
     * Gets an item by its ID (id field).
     * This is used when the frontend sends the ID from the API response.
     *
     * @param id the item ID (id field value)
     * @return Mono emitting the DatasetItem, or empty if not found
     */
    Mono<DatasetItem> getItemById(UUID id);

    /**
     * Mapping from row ID to dataset_item_id.
     */
    record DatasetItemIdMapping(UUID rowId, UUID datasetItemId, UUID datasetId) {
    }

}

@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Slf4j
class DatasetItemVersionDAOImpl implements DatasetItemVersionDAO {

    private static final String DATASET_ITEM_VERSIONS = "dataset_item_versions";
    private static final String CLICKHOUSE = "Clickhouse";

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

    // Insert new items directly (not copying from existing)
    private static final String INSERT_ITEM = """
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

    // Batch update items using INSERT ... SELECT with conditional field updates
    // Similar to legacy table's bulk update but for versioned items
    private static final String BATCH_UPDATE_ITEMS = """
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
                :newVersionId as dataset_version_id,
                <if(data)> :data <else> src.data <endif> as data,
                src.metadata,
                src.source,
                src.trace_id,
                src.span_id,
                <if(tags)><if(merge_tags)>arrayConcat(src.tags, :tags)<else>:tags<endif><else>src.tags<endif> as tags,
                src.item_created_at,
                now64(9) as item_last_updated_at,
                src.item_created_by,
                :userName as item_last_updated_by,
                now64(9) as created_at,
                now64(9) as last_updated_at,
                :userName as created_by,
                :userName as last_updated_by,
                src.workspace_id
            FROM (
                SELECT *
                FROM dataset_item_versions
                WHERE workspace_id = :workspace_id
                AND dataset_id = :datasetId
                AND dataset_version_id = :baseVersionId
                AND dataset_item_id IN :itemIds
                ORDER BY (workspace_id, dataset_id, dataset_version_id, id) DESC, last_updated_at DESC
                LIMIT 1 BY dataset_item_id
            ) AS src
            """;

    // Copy items from source version to target version
    // Optionally excludes items matching filters (when exclude_filters is set)
    // Optionally excludes specific item IDs (when exclude_ids is set)
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
                <if(exclude_ids)>
                AND dataset_item_id NOT IN :excludedIds
                <endif>
                ORDER BY (workspace_id, dataset_id, dataset_version_id, id) DESC, last_updated_at DESC
                LIMIT 1 BY id
            ) AS src
            """;

    private static final String RESOLVE_DATASET_ID_FROM_ITEM_ID = """
            SELECT
                dataset_id
            FROM dataset_item_versions
            WHERE dataset_item_id = :datasetItemId
            AND workspace_id = :workspace_id
            ORDER BY (workspace_id, dataset_id, dataset_version_id, id) DESC, last_updated_at DESC
            LIMIT 1
            """;

    private static final String SELECT_COLUMNS_BY_VERSION = """
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
                FROM dataset_item_versions
                WHERE dataset_id = :datasetId
                AND dataset_version_id = :versionId
                AND workspace_id = :workspace_id
                ORDER BY (workspace_id, dataset_id, dataset_version_id, id) DESC, last_updated_at DESC
                LIMIT 1 BY id
            ) AS lastRows
            """;

    private static final String SELECT_ITEM_ID_MAPPING_BY_ROW_IDS = """
            SELECT
                id,
                dataset_item_id,
                dataset_id
            FROM dataset_item_versions
            WHERE id IN :rowIds
            AND workspace_id = :workspace_id
            ORDER BY (workspace_id, dataset_id, dataset_version_id, id) DESC, last_updated_at DESC
            LIMIT 1 BY id
            """;

    private static final String SELECT_ITEM_BY_ID = """
            SELECT
                id,
                dataset_item_id,
                dataset_id,
                data,
                source,
                trace_id,
                span_id,
                tags,
                item_created_at as created_at,
                item_last_updated_at as last_updated_at,
                item_created_by as created_by,
                item_last_updated_by as last_updated_by
            FROM dataset_item_versions
            WHERE workspace_id = :workspace_id
            AND id = :id
            ORDER BY (workspace_id, dataset_id, dataset_version_id, id) DESC, last_updated_at DESC
            LIMIT 1
            """;

    private static final String SELECT_ITEMS_BY_DATASET_ITEM_IDS = """
            SELECT
                id,
                dataset_item_id,
                dataset_id,
                data,
                source,
                trace_id,
                span_id,
                tags,
                item_created_at as created_at,
                item_last_updated_at as last_updated_at,
                item_created_by as created_by,
                item_last_updated_by as last_updated_by
            FROM dataset_item_versions
            WHERE workspace_id = :workspace_id
            AND dataset_id = :datasetId
            AND dataset_version_id = :versionId
            AND dataset_item_id IN :datasetItemIds
            ORDER BY (workspace_id, dataset_id, dataset_version_id, id) DESC, last_updated_at DESC
            LIMIT 1 BY dataset_item_id
            """;

    private final @NonNull TransactionTemplateAsync asyncTemplate;
    private final @NonNull IdGenerator idGenerator;

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
                getColumns(criteria.datasetId(), versionId)).flatMap(tuple -> {
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
            @NonNull UUID newVersionId, @NonNull List<DatasetItem> addedItems,
            @NonNull List<DatasetItem> editedItems, @NonNull Set<UUID> deletedIds,
            int baseVersionItemCount) {

        log.info("Applying delta for dataset '{}': baseVersion='{}', newVersion='{}', " +
                "added='{}', edited='{}', deleted='{}', baseItemCount='{}'",
                datasetId, baseVersionId, newVersionId, addedItems.size(), editedItems.size(),
                deletedIds.size(), baseVersionItemCount);

        // Collect all stable item IDs that are being edited (so we don't copy them from base)
        Set<UUID> editedItemIds = editedItems.stream()
                .map(DatasetItem::draftItemId)
                .collect(Collectors.toSet());

        // Combine deleted and edited IDs for exclusion when copying
        Set<UUID> excludedIds = new HashSet<>(deletedIds);
        excludedIds.addAll(editedItemIds);

        // Generate UUID pool for worst-case scenario (all base items copied)
        // We can't know how many excludedIds actually exist in base version,
        // so we generate enough UUIDs for all items to prevent running out during copy
        List<UUID> unchangedUuids = generateUuidPool(idGenerator, baseVersionItemCount);

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

        // Use the unified COPY_VERSION_ITEMS template with exclude_ids
        return asyncTemplate.nonTransaction(connection -> {
            String[] excludedIdStrings = excludedIds.stream()
                    .map(UUID::toString)
                    .toArray(String[]::new);

            String[] uuidStrings = uuids.stream()
                    .map(UUID::toString)
                    .toArray(String[]::new);

            // Build query using StringTemplate
            ST template = new ST(COPY_VERSION_ITEMS);
            template.add("exclude_ids", true);
            String query = template.render();

            var statement = connection.createStatement(query)
                    .bind("datasetId", datasetId.toString())
                    .bind("sourceVersionId", baseVersionId.toString())
                    .bind("targetVersionId", newVersionId.toString())
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
    public Mono<Long> batchUpdateItems(@NonNull UUID datasetId, @NonNull UUID baseVersionId,
            @NonNull UUID newVersionId, @NonNull Set<UUID> itemIds, @NonNull DatasetItemUpdate update,
            Boolean mergeTags, @NonNull List<UUID> uuids) {

        if (itemIds.isEmpty()) {
            return Mono.just(0L);
        }

        log.info("Batch updating '{}' items in dataset '{}' from version '{}' to version '{}'",
                itemIds.size(), datasetId, baseVersionId, newVersionId);

        return Mono.deferContextual(ctx -> {
            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);
            String userName = ctx.get(RequestContext.USER_NAME);

            return asyncTemplate.nonTransaction(connection -> {
                // Build query using StringTemplate for conditional fields
                ST template = new ST(BATCH_UPDATE_ITEMS);

                // Add conditional parameters based on what fields are being updated
                if (update.data() != null) {
                    template.add("data", true);
                }
                if (update.tags() != null) {
                    template.add("tags", true);
                    if (Boolean.TRUE.equals(mergeTags)) {
                        template.add("merge_tags", true);
                    }
                }

                String query = template.render();

                // Convert UUIDs to strings for ClickHouse
                String[] itemIdStrings = itemIds.stream()
                        .map(UUID::toString)
                        .toArray(String[]::new);
                String[] uuidStrings = uuids.stream()
                        .map(UUID::toString)
                        .toArray(String[]::new);

                var statement = connection.createStatement(query)
                        .bind("workspace_id", workspaceId)
                        .bind("datasetId", datasetId.toString())
                        .bind("baseVersionId", baseVersionId.toString())
                        .bind("newVersionId", newVersionId.toString())
                        .bind("itemIds", itemIdStrings)
                        .bind("uuids", uuidStrings)
                        .bind("userName", userName);

                // Bind optional update fields
                if (update.data() != null) {
                    Map<String, String> dataAsStrings = DatasetItemResultMapper.getOrDefault(update.data());
                    statement.bind("data", dataAsStrings);
                }
                if (update.tags() != null) {
                    statement.bind("tags", update.tags().toArray(new String[0]));
                }

                Segment segment = startSegment(DATASET_ITEM_VERSIONS, CLICKHOUSE, "batch_update_items");

                return Flux.from(statement.execute())
                        .flatMap(Result::getRowsUpdated)
                        .reduce(0L, Long::sum)
                        .doOnSuccess(count -> log.info("Batch updated '{}' items in dataset '{}'", count, datasetId))
                        .doFinally(signalType -> endSegment(segment));
            });
        });
    }

    @Override
    @WithSpan
    public Mono<Long> insertItems(@NonNull UUID datasetId, @NonNull UUID newVersionId,
            @NonNull List<DatasetItem> items, @NonNull String workspaceId, @NonNull String userName) {

        if (items.isEmpty()) {
            return Mono.just(0L);
        }

        // Note: ClickHouse with async inserts returns 0 immediately before commit.
        // We return the count of items we're inserting instead of relying on getRowsUpdated.
        long itemCount = items.size();

        return asyncTemplate.nonTransaction(connection -> {
            Segment segment = startSegment(DATASET_ITEM_VERSIONS, CLICKHOUSE, "insert_delta_items");

            return Flux.fromIterable(items)
                    .flatMap(item -> {
                        UUID rowId = UUID.randomUUID();
                        UUID stableItemId = item.draftItemId();
                        Map<String, String> dataAsStrings = DatasetItemResultMapper.getOrDefault(item.data());
                        var statement = connection.createStatement(INSERT_ITEM)
                                .bind("id", rowId.toString())
                                .bind("dataset_item_id", stableItemId.toString())
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
                                .doOnError(
                                        e -> log.error("Insert failed for item dataset_item_id='{}'", stableItemId, e));
                    })
                    .collectList()
                    .map(results -> itemCount) // Return item count instead of sum of results
                    .doOnSuccess(count -> log.debug("Inserted '{}' items", count))
                    .doOnError(e -> log.error("Insert items failed", e))
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
    public Mono<UUID> resolveDatasetIdFromItemId(@NonNull UUID datasetItemId) {
        log.debug("Resolving dataset ID for item '{}'", datasetItemId);

        return asyncTemplate.nonTransaction(connection -> {
            var statement = connection.createStatement(RESOLVE_DATASET_ID_FROM_ITEM_ID)
                    .bind("datasetItemId", datasetItemId.toString());

            Segment segment = startSegment(DATASET_ITEM_VERSIONS, CLICKHOUSE, "resolve_dataset_id_from_item_id");

            return makeMonoContextAware((userName, workspaceId) -> {
                statement.bind("workspace_id", workspaceId);

                return Flux.from(statement.execute())
                        .flatMap(result -> result
                                .map((row, rowMetadata) -> UUID.fromString(row.get("dataset_id", String.class))))
                        .next()
                        .doOnSuccess(datasetId -> {
                            if (datasetId != null) {
                                log.debug("Resolved dataset '{}' for item '{}'", datasetId, datasetItemId);
                            } else {
                                log.debug("No dataset found for item '{}'", datasetItemId);
                            }
                        })
                        .doFinally(signalType -> endSegment(segment));
            });
        });
    }

    @Override
    @WithSpan
    public Mono<DatasetItem> getItemByDatasetItemId(@NonNull UUID datasetId, @NonNull UUID versionId,
            @NonNull UUID datasetItemId) {
        log.debug("Getting item by dataset_item_id '{}' from dataset '{}' version '{}'", datasetItemId, datasetId,
                versionId);

        // Use the batch method with a single item for consistency
        return getItemsByDatasetItemIds(datasetId, versionId, Set.of(datasetItemId)).next();
    }

    @Override
    @WithSpan
    public Flux<DatasetItem> getItemsByDatasetItemIds(@NonNull UUID datasetId, @NonNull UUID versionId,
            @NonNull Set<UUID> datasetItemIds) {
        if (datasetItemIds.isEmpty()) {
            return Flux.empty();
        }

        log.debug("Getting '{}' items by dataset_item_ids from dataset '{}' version '{}'", datasetItemIds.size(),
                datasetId, versionId);

        // Convert UUIDs to strings for the IN clause
        List<String> itemIdStrings = datasetItemIds.stream()
                .map(UUID::toString)
                .toList();

        return asyncTemplate.stream(connection -> {
            var statement = connection.createStatement(SELECT_ITEMS_BY_DATASET_ITEM_IDS)
                    .bind("datasetId", datasetId.toString())
                    .bind("versionId", versionId.toString())
                    .bind("datasetItemIds", itemIdStrings);

            Segment segment = startSegment(DATASET_ITEM_VERSIONS, CLICKHOUSE, "get_items_by_dataset_item_ids");

            return makeFluxContextAware(bindWorkspaceIdToFlux(statement))
                    .doFinally(signalType -> endSegment(segment))
                    .flatMap(result -> result.map((row, rowMetadata) -> mapVersionedItemToDatasetItem(row)));
        });
    }

    private DatasetItem mapVersionedItemToDatasetItem(io.r2dbc.spi.Row row) {
        // Map data field - stored as Map<String, String> in ClickHouse
        Map<String, com.fasterxml.jackson.databind.JsonNode> data = Optional.ofNullable(row.get("data", Map.class))
                .filter(m -> !m.isEmpty())
                .map(value -> (Map<String, String>) value)
                .stream()
                .map(Map::entrySet)
                .flatMap(java.util.Collection::stream)
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> com.comet.opik.utils.JsonUtils.getJsonNodeFromStringWithFallback(entry.getValue())));

        return DatasetItem.builder()
                .id(UUID.fromString(row.get("id", String.class)))
                .draftItemId(UUID.fromString(row.get("dataset_item_id", String.class)))
                .datasetId(UUID.fromString(row.get("dataset_id", String.class)))
                .data(data.isEmpty() ? null : data)
                .source(Optional.ofNullable(row.get("source", String.class))
                        .map(com.comet.opik.api.DatasetItemSource::fromString)
                        .orElse(null))
                .traceId(Optional.ofNullable(row.get("trace_id", String.class))
                        .filter(s -> !s.isBlank())
                        .map(UUID::fromString)
                        .orElse(null))
                .spanId(Optional.ofNullable(row.get("span_id", String.class))
                        .filter(s -> !s.isBlank())
                        .map(UUID::fromString)
                        .orElse(null))
                .tags(Optional.ofNullable(row.get("tags", String[].class))
                        .map(java.util.Arrays::asList)
                        .map(Set::copyOf)
                        .orElse(null))
                .createdAt(row.get("created_at", Instant.class))
                .lastUpdatedAt(row.get("last_updated_at", Instant.class))
                .createdBy(row.get("created_by", String.class))
                .lastUpdatedBy(row.get("last_updated_by", String.class))
                .build();
    }

    private Mono<Set<Column>> getColumns(UUID datasetId, UUID versionId) {
        log.debug("Getting columns for dataset '{}', version '{}'", datasetId, versionId);

        return asyncTemplate.nonTransaction(connection -> {
            var statement = connection.createStatement(SELECT_COLUMNS_BY_VERSION)
                    .bind("datasetId", datasetId.toString())
                    .bind("versionId", versionId.toString());

            Segment segment = startSegment(DATASET_ITEM_VERSIONS, CLICKHOUSE, "get_columns_by_version");

            return makeMonoContextAware((userName, workspaceId) -> {
                statement.bind("workspace_id", workspaceId);

                return Flux.from(statement.execute())
                        .flatMap(result -> DatasetItemResultMapper.mapColumns(result, "data"))
                        .next()
                        .defaultIfEmpty(Set.of())
                        .doFinally(signalType -> endSegment(segment));
            });
        });
    }

    @Override
    @WithSpan
    public Flux<DatasetItemIdMapping> mapRowIdsToDatasetItemIds(@NonNull Set<UUID> rowIds) {
        if (rowIds.isEmpty()) {
            return Flux.empty();
        }

        log.debug("Mapping '{}' row IDs to dataset_item_ids", rowIds.size());

        // Convert UUIDs to strings for the IN clause
        List<String> rowIdStrings = rowIds.stream()
                .map(UUID::toString)
                .toList();

        return asyncTemplate.stream(connection -> {
            var statement = connection.createStatement(SELECT_ITEM_ID_MAPPING_BY_ROW_IDS)
                    .bind("rowIds", rowIdStrings);

            Segment segment = startSegment(DATASET_ITEM_VERSIONS, CLICKHOUSE, "map_row_ids_to_dataset_item_ids");

            return makeFluxContextAware(bindWorkspaceIdToFlux(statement))
                    .doFinally(signalType -> endSegment(segment))
                    .flatMap(result -> result.map((row, rowMetadata) -> new DatasetItemIdMapping(
                            UUID.fromString(row.get("id", String.class)),
                            UUID.fromString(row.get("dataset_item_id", String.class)),
                            UUID.fromString(row.get("dataset_id", String.class)))));
        });
    }

    @Override
    @WithSpan
    public Mono<DatasetItem> getItemById(@NonNull UUID id) {
        log.debug("Getting item by ID '{}'", id);

        return asyncTemplate.nonTransaction(connection -> {
            var statement = connection.createStatement(SELECT_ITEM_BY_ID)
                    .bind("id", id.toString());

            Segment segment = startSegment(DATASET_ITEM_VERSIONS, CLICKHOUSE, "get_item_by_id");

            return makeMonoContextAware((userName, workspaceId) -> {
                statement.bind("workspace_id", workspaceId);

                return Flux.from(statement.execute())
                        .flatMap(result -> result.map((row, rowMetadata) -> mapVersionedItemToDatasetItem(row)))
                        .next()
                        .doOnSuccess(item -> {
                            if (item != null) {
                                log.debug("Found item by ID '{}'", id);
                            } else {
                                log.debug("Item not found by ID '{}'", id);
                            }
                        })
                        .doFinally(signalType -> endSegment(segment));
            });
        });
    }

}

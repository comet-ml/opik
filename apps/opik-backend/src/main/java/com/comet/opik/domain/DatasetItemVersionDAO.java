package com.comet.opik.domain;

import com.comet.opik.api.Column;
import com.comet.opik.api.DatasetItem;
import com.comet.opik.api.DatasetItem.DatasetItemPage;
import com.comet.opik.api.DatasetItemBatchUpdate;
import com.comet.opik.api.ProjectStats;
import com.comet.opik.api.filter.DatasetItemFilter;
import com.comet.opik.api.filter.ExperimentsComparisonFilter;
import com.comet.opik.api.filter.Filter;
import com.comet.opik.api.sorting.SortingFactoryDatasets;
import com.comet.opik.domain.filter.FilterQueryBuilder;
import com.comet.opik.domain.filter.FilterStrategy;
import com.comet.opik.domain.sorting.SortingQueryBuilder;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.db.TransactionTemplateAsync;
import com.comet.opik.utils.template.TemplateUtils;
import com.google.inject.ImplementedBy;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import io.r2dbc.spi.Result;
import io.r2dbc.spi.Statement;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.stringtemplate.v4.ST;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.comet.opik.domain.AsyncContextUtils.bindWorkspaceIdToFlux;
import static com.comet.opik.infrastructure.instrumentation.InstrumentAsyncUtils.Segment;
import static com.comet.opik.infrastructure.instrumentation.InstrumentAsyncUtils.endSegment;
import static com.comet.opik.infrastructure.instrumentation.InstrumentAsyncUtils.startSegment;
import static com.comet.opik.utils.AsyncUtils.makeFluxContextAware;
import static com.comet.opik.utils.AsyncUtils.makeMonoContextAware;

@ImplementedBy(DatasetItemVersionDAOImpl.class)
public interface DatasetItemVersionDAO {
    Mono<DatasetItemPage> getItems(DatasetItemSearchCriteria searchCriteria, int page, int size, UUID versionId);

    /**
     * Get dataset items with their associated experiment items.
     * This method joins dataset items with experiment items, traces, feedback scores, and comments.
     *
     * @param searchCriteria the search criteria including experiment IDs
     * @param page the page number
     * @param size the page size
     * @param versionId the dataset version ID
     * @return a Mono containing the page of dataset items with experiment items
     */
    Mono<DatasetItemPage> getItemsWithExperimentItems(DatasetItemSearchCriteria searchCriteria, int page, int size,
            UUID versionId);

    Mono<List<Column>> getExperimentItemsOutputColumns(UUID datasetId, Set<UUID> experimentIds);

    Mono<ProjectStats> getExperimentItemsStats(UUID datasetId, UUID versionId, Set<UUID> experimentIds,
            List<ExperimentsComparisonFilter> filters);

    Flux<DatasetItem> getItems(UUID datasetId, UUID versionId, int limit, UUID lastRetrievedId);

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
     * - Use {@code datasetItemId} field as the stable ID (maintained across versions)
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
    /**
     * Apply delta changes to create a new dataset version.
     * Added and edited items should already have their row IDs (id field) set.
     * Unchanged items will be copied with UUIDs from unchangedUuids.
     *
     * @param datasetId         Dataset ID
     * @param baseVersionId     Base version ID to copy unchanged items from
     * @param newVersionId      New version ID to create
     * @param addedItems        Items to add (with id already set)
     * @param editedItems       Items to edit (with id already set)
     * @param deletedIds        Stable dataset_item_ids to delete
     * @param unchangedUuids    UUIDs to assign to unchanged items (pre-generated in correct order)
     * @return Number of items in the new version
     */
    Mono<Long> applyDelta(UUID datasetId, UUID baseVersionId, UUID newVersionId,
            List<DatasetItem> addedItems, List<DatasetItem> editedItems, Set<UUID> deletedIds,
            List<UUID> unchangedUuids);

    /**
     * Applies batch updates to items from a base version, creating updated copies in a new version.
     * This is an efficient database-side operation using INSERT ... SELECT with conditional updates.
     * <p>
     * Supports both ID-based updates (via batchUpdate.ids()) and filter-based updates (via batchUpdate.filters()).
     * Only non-null fields in the update are applied.
     *
     * @param datasetId the dataset ID
     * @param baseVersionId the base version to copy from
     * @param newVersionId the new version to insert into
     * @param batchUpdate the batch update containing either IDs or filters and the update to apply
     * @param uuids pre-generated UUIDv7 pool for new row IDs
     * @return the number of items updated
     */
    Mono<Long> batchUpdateItems(UUID datasetId, UUID baseVersionId, UUID newVersionId,
            DatasetItemBatchUpdate batchUpdate, List<UUID> uuids);

    /**
     * Inserts items directly into a new version without copying from any base version.
     * <p>
     * For items passed to this method:
     * - Use {@code datasetItemId} field as the stable ID (maintained across versions)
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
     * Removes items from an existing version in ClickHouse.
     * This is used for batch delete operations where multiple batches share the same batch_group_id.
     *
     * @param datasetId the dataset ID
     * @param versionId the version ID to remove items from
     * @param itemIds the set of dataset_item_id values to remove
     * @param workspaceId the workspace ID
     * @return the number of items removed
     */
    Mono<Long> removeItemsFromVersion(UUID datasetId, UUID versionId, Set<UUID> itemIds, String workspaceId);

    /**
     * Removes items from an existing version in ClickHouse based on filters.
     * This is used for filter-based delete operations where items matching the filters should be removed.
     *
     * @param datasetId the dataset ID
     * @param versionId the version ID to remove items from
     * @param filters the filters to match items to remove
     * @param workspaceId the workspace ID
     * @return the number of items removed
     */
    Mono<Long> removeItemsFromVersionByFilters(UUID datasetId, UUID versionId, List<DatasetItemFilter> filters,
            String workspaceId);

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
     * Gets workspace IDs for dataset item row IDs (id field from dataset_item_versions).
     * Used for validating that dataset items belong to the correct workspace.
     *
     * @param datasetItemRowIds the row IDs (id field values from dataset_item_versions)
     * @return Mono emitting a list of workspace and resource ID pairs
     */
    Mono<List<WorkspaceAndResourceId>> getDatasetItemWorkspace(Set<UUID> datasetItemRowIds);

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
                data_hash,
                tags
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
                <if(truncate)> mapApply((k, v) -> (k, substring(replaceRegexpAll(v, '<truncate>', '"[image]"'), 1, <truncationSize>)), data) as data <else> data <endif>,
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
            <if(lastRetrievedId)>AND id \\< :lastRetrievedId<endif>
            <if(dataset_item_filters)>AND (<dataset_item_filters>)<endif>
            ORDER BY (workspace_id, dataset_id, dataset_version_id, id) DESC, last_updated_at DESC
            LIMIT 1 BY id
            <if(lastRetrievedId)>
            LIMIT :limit
            <else>
            LIMIT :limit OFFSET :offset
            <endif>
            """;

    private static final String SELECT_DATASET_ITEM_VERSIONS_COUNT = """
            SELECT count(DISTINCT id) as count
            FROM dataset_item_versions
            WHERE dataset_id = :datasetId
            AND dataset_version_id = :versionId
            AND workspace_id = :workspace_id
            <if(dataset_item_filters)>AND (<dataset_item_filters>)<endif>
            """;

    private static final String DELETE_ITEMS_FROM_VERSION = """
            DELETE FROM dataset_item_versions
            WHERE dataset_id = :dataset_id
              AND dataset_version_id = :version_id
              AND workspace_id = :workspace_id
              <if(item_ids)>AND dataset_item_id IN (:item_ids)<endif>
              <if(dataset_item_filters)>AND (<dataset_item_filters>)<endif>
            """;

    private static final String COUNT_ITEMS = """
            SELECT count(DISTINCT dataset_item_id) as count
            FROM dataset_item_versions
            WHERE dataset_id = :dataset_id
              AND dataset_version_id = :version_id
              AND workspace_id = :workspace_id
              <if(item_ids)>AND dataset_item_id IN :item_ids<endif>
              <if(dataset_item_filters)>AND (<dataset_item_filters>)<endif>
            """;

    /**
     * Counts dataset items with experiment items, applying all filters from search criteria.
     * This ensures pagination totals match the filtered results.
     *
     * Note: Uses simplified feedback scores processing (only aggregated values, not full details)
     * since we only need values for filtering in HAVING clauses, not for display.
     * This keeps the count query closer to the legacy pattern while supporting all required filters.
     */
    private static final String SELECT_DATASET_ITEM_VERSIONS_WITH_EXPERIMENT_ITEMS_COUNT = """
            WITH experiment_items_scope AS (
            	SELECT *
            	FROM experiment_items
            	WHERE workspace_id = :workspace_id
            	<if(experiment_ids)>AND experiment_id IN :experiment_ids<endif>
            	ORDER BY id DESC, last_updated_at DESC
            	LIMIT 1 BY id
            ),
            feedback_scores_combined_raw AS (
                SELECT workspace_id,
                       project_id,
                       entity_id,
                       name,
                       value,
                       last_updated_at,
                       feedback_scores.last_updated_by AS author
                FROM feedback_scores FINAL
                WHERE entity_type = 'trace'
                  AND workspace_id = :workspace_id
                  AND entity_id IN (SELECT trace_id FROM experiment_items_scope)
                UNION ALL
                SELECT workspace_id,
                       project_id,
                       entity_id,
                       name,
                       value,
                       last_updated_at,
                       author
                FROM authored_feedback_scores FINAL
                WHERE entity_type = 'trace'
                  AND workspace_id = :workspace_id
                  AND entity_id IN (SELECT trace_id FROM experiment_items_scope)
            ),
            feedback_scores_with_ranking AS (
                SELECT workspace_id,
                       project_id,
                       entity_id,
                       name,
                       value,
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
                       value,
                       last_updated_at
                FROM feedback_scores_with_ranking
                WHERE rn = 1
            ),
            feedback_scores_final AS (
                SELECT
                    workspace_id,
                    project_id,
                    entity_id,
                    name,
                    if(count() = 1, any(value), toDecimal64(avg(value), 9)) AS value,
                    max(last_updated_at) AS last_updated_at
                FROM feedback_scores_combined
                GROUP BY workspace_id, project_id, entity_id, name
            )
            <if(feedback_scores_empty_filters)>
            , fsc AS (
                SELECT entity_id, COUNT(entity_id) AS feedback_scores_count
                FROM feedback_scores_final
                GROUP BY entity_id
                HAVING <feedback_scores_empty_filters>
            )
            <endif>
            , experiment_items_final AS (
            	SELECT *
            	FROM experiment_items_scope ei
            	WHERE workspace_id = :workspace_id
            	<if(experiment_item_filters || feedback_scores_filters || feedback_scores_empty_filters || dataset_item_filters)>
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
                <if(dataset_item_filters)>
                AND ei.dataset_item_id IN (
                    SELECT div.id
                    FROM dataset_item_versions div
                    INNER JOIN experiment_items_scope ei_inner ON ei_inner.dataset_item_id = div.id
                    LEFT JOIN experiments e ON e.id = ei_inner.experiment_id AND e.workspace_id = :workspace_id
                    WHERE div.workspace_id = :workspace_id
                    AND div.dataset_id = :datasetId
                    AND div.dataset_version_id = COALESCE(nullIf(e.dataset_version_id, ''), :versionId)
                    AND <dataset_item_filters>
                    ORDER BY (div.workspace_id, div.dataset_id, div.dataset_version_id, div.id) DESC, div.last_updated_at DESC
                    LIMIT 1 BY div.id
                )
                <endif>
            	ORDER BY id DESC, last_updated_at DESC
            )
            SELECT COUNT(DISTINCT ei.dataset_item_id) AS count
            FROM experiment_items_final AS ei
            LEFT JOIN (
                SELECT
                    div.id AS id,
                    div.dataset_item_id AS dataset_item_id,
                    div.data AS data
                FROM dataset_item_versions div
                INNER JOIN experiment_items_scope ei_inner ON ei_inner.dataset_item_id = div.id
                LEFT JOIN experiments e ON e.id = ei_inner.experiment_id AND e.workspace_id = :workspace_id
                WHERE div.workspace_id = :workspace_id
                AND div.dataset_id = :datasetId
                AND div.dataset_version_id = COALESCE(nullIf(e.dataset_version_id, ''), :versionId)
                ORDER BY (div.workspace_id, div.dataset_id, div.dataset_version_id, div.id) DESC, div.last_updated_at DESC
                LIMIT 1 BY div.id
            ) AS di ON di.id = ei.dataset_item_id
            LEFT JOIN (
                SELECT
                    t.id,
                    <if(truncate)> substring(replaceRegexpAll(input, '<truncate>', '"[image]"'), 1, <truncationSize>) as input <else> input <endif>,
                    <if(truncate)> substring(replaceRegexpAll(output, '<truncate>', '"[image]"'), 1, <truncationSize>) as output <else> output <endif>
                FROM (
                    SELECT
                        id,
                        input,
                        output
                    FROM traces
                    WHERE workspace_id = :workspace_id
                    AND id IN (SELECT trace_id FROM experiment_items_final)
                    ORDER BY (workspace_id, project_id, id) DESC, last_updated_at DESC
                    LIMIT 1 BY id
                ) AS t
            ) AS tfs ON ei.trace_id = tfs.id
            <if(search)>
            WHERE multiSearchAnyCaseInsensitive(toString(COALESCE(di.data, map())), :searchTerms) OR multiSearchAnyCaseInsensitive(toString(tfs.input), :searchTerms) OR multiSearchAnyCaseInsensitive(toString(tfs.output), :searchTerms)
            <endif>
            """;

    // Query to extract columns from trace output for experiment items view
    private static final String SELECT_EXPERIMENT_ITEMS_OUTPUT_COLUMNS = """
            WITH dataset_items_scope AS (
                SELECT DISTINCT
                    div.id AS row_id,
                    div.dataset_item_id AS stable_id,
                    div.dataset_version_id
                FROM experiments e
                INNER JOIN dataset_item_versions div
                    ON div.dataset_id = :datasetId
                    AND div.workspace_id = :workspace_id
                    AND div.dataset_version_id = e.dataset_version_id
                WHERE e.workspace_id = :workspace_id
                <if(experiment_ids)>AND e.id IN :experiment_ids<endif>
                ORDER BY (div.workspace_id, div.dataset_id, div.dataset_version_id, div.id) DESC, div.last_updated_at DESC
                LIMIT 1 BY div.id
            ),
            experiment_items_scope AS (
                SELECT
                    trace_id,
                    dataset_item_id
                FROM experiment_items
                WHERE workspace_id = :workspace_id
                AND dataset_item_id IN (SELECT row_id FROM dataset_items_scope)
                <if(experiment_ids)>AND experiment_id IN :experiment_ids<endif>
                ORDER BY id DESC, last_updated_at DESC
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
                                    key_type -> map(tupleElement(key_type, 1), [tupleElement(key_type, 2)]),
                                    output_keys
                                )
                            )
                        )
                    ),
                    CAST(map(), 'Map(String, Array(String))')
                ) AS columns
            FROM experiment_items_scope AS ei
            INNER JOIN (
                SELECT
                    id,
                    output_keys
                FROM traces FINAL
                WHERE workspace_id = :workspace_id
                AND id IN (SELECT trace_id FROM experiment_items_scope)
            ) AS t ON t.id = ei.trace_id
            """;

    // Query to fetch versioned dataset items with their associated experiment items
    private static final String SELECT_DATASET_ITEM_VERSIONS_WITH_EXPERIMENT_ITEMS = """
            WITH experiment_items_scope AS (
            	SELECT *
            	FROM experiment_items
            	WHERE workspace_id = :workspace_id
            	<if(experiment_ids)>AND experiment_id IN :experiment_ids<endif>
            	ORDER BY id DESC, last_updated_at DESC
            	LIMIT 1 BY id
            ),
            experiment_items_final AS (
            	SELECT *
            	FROM experiment_items_scope ei
            	WHERE workspace_id = :workspace_id
            	<if(experiment_item_filters || feedback_scores_filters || feedback_scores_empty_filters || dataset_item_filters)>
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
                <if(dataset_item_filters)>
                AND ei.dataset_item_id IN (
                    SELECT div.id
                    FROM dataset_item_versions div
                    INNER JOIN experiment_items_scope ei_inner ON ei_inner.dataset_item_id = div.id
                    LEFT JOIN experiments e ON e.id = ei_inner.experiment_id AND e.workspace_id = :workspace_id
                    WHERE div.workspace_id = :workspace_id
                    AND div.dataset_id = :datasetId
                    AND div.dataset_version_id = COALESCE(nullIf(e.dataset_version_id, ''), :versionId)
                    AND <dataset_item_filters>
                    ORDER BY (div.workspace_id, div.dataset_id, div.dataset_version_id, div.id) DESC, div.last_updated_at DESC
                    LIMIT 1 BY div.id
                )
                <endif>
            	ORDER BY id DESC, last_updated_at DESC
            ),
            feedback_scores_combined_raw AS (
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
                WHERE entity_type = 'trace'
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
            ),
            feedback_scores_combined_grouped AS (
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
            ),
            feedback_scores_final AS (
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
            <if(feedback_scores_empty_filters)>
            , fsc AS (
                SELECT entity_id, COUNT(entity_id) AS feedback_scores_count
                FROM feedback_scores_final
                GROUP BY entity_id
                HAVING <feedback_scores_empty_filters>
            )
            <endif>
            , comments_final AS (
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
                di.item_created_at AS created_at,
                di.item_last_updated_at AS last_updated_at,
                di.item_created_by AS created_by,
                di.item_last_updated_by AS last_updated_by,
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
            LEFT JOIN (
                SELECT
                    div.id AS id,
                    div.dataset_item_id AS dataset_item_id,
                    div.data AS data,
                    div.trace_id AS trace_id,
                    div.span_id AS span_id,
                    div.source AS source,
                    div.tags AS tags,
                    div.item_created_at AS item_created_at,
                    div.item_last_updated_at AS item_last_updated_at,
                    div.item_created_by AS item_created_by,
                    div.item_last_updated_by AS item_last_updated_by
                FROM dataset_item_versions div
                INNER JOIN experiment_items_scope ei_inner ON ei_inner.dataset_item_id = div.id
                LEFT JOIN experiments e ON e.id = ei_inner.experiment_id AND e.workspace_id = :workspace_id
                WHERE div.workspace_id = :workspace_id
                AND div.dataset_id = :datasetId
                AND div.dataset_version_id = COALESCE(nullIf(e.dataset_version_id, ''), :versionId)
                ORDER BY (div.workspace_id, div.dataset_id, div.dataset_version_id, div.id) DESC, div.last_updated_at DESC
                LIMIT 1 BY div.id
            ) AS di ON di.id = ei.dataset_item_id
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
                di.item_created_at,
                di.item_last_updated_at,
                di.item_created_by,
                di.item_last_updated_by
            <if(search)>
            HAVING multiSearchAnyCaseInsensitive(toString(data_final), :searchTerms) OR multiSearchAnyCaseInsensitive(toString(input), :searchTerms) OR multiSearchAnyCaseInsensitive(toString(output), :searchTerms)
            <endif>
            <if(filters)>
            HAVING <filters>
            <endif>
            <if(sorting)>
            ORDER BY <sorting>
            <else>
            ORDER BY created_at DESC
            <endif>
            LIMIT :limit
            OFFSET :offset
            """;

    // Batch insert items
    private static final String BATCH_INSERT_ITEMS = """
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
            ) VALUES
                <items:{item |
                    (
                        :id<item.index>,
                        :dataset_item_id<item.index>,
                        :dataset_id,
                        :dataset_version_id,
                        :data<item.index>,
                        :metadata<item.index>,
                        :source<item.index>,
                        :trace_id<item.index>,
                        :span_id<item.index>,
                        :tags<item.index>,
                        :item_created_at<item.index>,
                        :item_last_updated_at<item.index>,
                        :item_created_by<item.index>,
                        :item_last_updated_by<item.index>,
                        now64(9),
                        now64(9),
                        :created_by,
                        :last_updated_by,
                        :workspace_id
                    )<if(item.hasNext)>,<endif>
                }>
            """;

    // Batch update items using INSERT ... SELECT with conditional field updates
    // Similar to legacy table's bulk update but for versioned items
    // Supports both ID-based and filter-based updates
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
                <if(item_ids)>
                AND dataset_item_id IN :itemIds
                <endif>
                <if(dataset_item_filters)>
                AND <dataset_item_filters>
                <endif>
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
                arrayElement(:uuids, row_number() OVER (ORDER BY src.id DESC)) as id,
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
            ORDER BY src.id DESC
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
                mapFromArrays(
                    groupArray(key),
                    groupArray(types)
                ) AS columns
            FROM (
                SELECT
                    key,
                    arrayDistinct(groupArray(type)) AS types
                FROM (
                    SELECT
                        id,
                        column_types
                    FROM dataset_item_versions FINAL
                    WHERE dataset_id = :datasetId
                    AND dataset_version_id = :versionId
                    AND workspace_id = :workspace_id
                ) AS lastRows
                ARRAY JOIN mapKeys(column_types) AS key
                ARRAY JOIN column_types[key] AS type
                GROUP BY key
            )
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

    private static final String SELECT_DATASET_WORKSPACE_ITEMS_BY_ROW_IDS = """
            SELECT DISTINCT
                id,
                workspace_id
            FROM dataset_item_versions
            WHERE id IN :datasetItemRowIds
            ORDER BY (workspace_id, dataset_id, dataset_version_id, id) DESC, last_updated_at DESC
            LIMIT 1 BY id
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

    private static final String SELECT_DATASET_ITEM_VERSIONS_WITH_EXPERIMENT_ITEMS_STATS = """
            WITH experiment_items_scope AS (
                SELECT *
                FROM experiment_items
                WHERE workspace_id = :workspace_id
                <if(experiment_ids)>AND experiment_id IN :experiment_ids<endif>
                ORDER BY id DESC, last_updated_at DESC
                LIMIT 1 BY id
            ), feedback_scores_combined_raw AS (
                SELECT workspace_id,
                       project_id,
                       entity_id,
                       name,
                       value,
                       last_updated_at,
                       feedback_scores.last_updated_by AS author
                FROM feedback_scores FINAL
                WHERE entity_type = 'trace'
                  AND workspace_id = :workspace_id
                UNION ALL
                SELECT
                    workspace_id,
                    project_id,
                    entity_id,
                    name,
                    value,
                    last_updated_at,
                    author
                FROM authored_feedback_scores FINAL
                WHERE entity_type = 'trace'
                   AND workspace_id = :workspace_id
            ), feedback_scores_with_ranking AS (
                SELECT workspace_id,
                       project_id,
                       entity_id,
                       name,
                       value,
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
                       value,
                       last_updated_at,
                       author
                FROM feedback_scores_with_ranking
                WHERE rn = 1
            ), feedback_scores_final AS (
                SELECT
                    workspace_id,
                    project_id,
                    entity_id,
                    name,
                    if(count() = 1, any(value), toDecimal64(avg(value), 9)) AS value,
                    max(last_updated_at) AS last_updated_at
                FROM feedback_scores_combined
                GROUP BY workspace_id, project_id, entity_id, name
            )<if(feedback_scores_empty_filters)>,
            fsc AS (
                SELECT entity_id, COUNT(entity_id) AS feedback_scores_count
                FROM (
                    SELECT *
                    FROM feedback_scores_final
                 )
                 GROUP BY entity_id
                 HAVING <feedback_scores_empty_filters>
            )
            <endif>,
            experiment_version_mapping AS (
                SELECT
                    ei.id AS experiment_item_id,
                    ei.experiment_id,
                    ei.dataset_item_id,
                    COALESCE(nullIf(e.dataset_version_id, ''), :versionId) AS resolved_version_id
                FROM experiment_items_scope ei
                LEFT JOIN experiments e ON e.id = ei.experiment_id AND e.workspace_id = :workspace_id
            ),
            dataset_items_by_version AS (
                SELECT
                    div.id,
                    div.dataset_item_id,
                    evm.resolved_version_id
                FROM dataset_item_versions div
                INNER JOIN experiment_version_mapping evm ON evm.dataset_item_id = div.id
                WHERE div.workspace_id = :workspace_id
                AND div.dataset_id = :datasetId
                AND div.dataset_version_id = evm.resolved_version_id
                ORDER BY (div.workspace_id, div.dataset_id, div.dataset_version_id, div.id) DESC, div.last_updated_at DESC
                LIMIT 1 BY div.id
            ),
            experiment_items_filtered AS (
                SELECT
                    ei.id,
                    ei.experiment_id,
                    ei.dataset_item_id,
                    ei.trace_id
                FROM experiment_items_scope ei
                INNER JOIN dataset_items_by_version dibv ON dibv.id = ei.dataset_item_id
                <if(experiment_item_filters)>
                AND ei.trace_id IN (
                    SELECT id FROM traces WHERE workspace_id = :workspace_id AND <experiment_item_filters>
                )
                <endif>
                <if(feedback_scores_empty_filters)>
                AND ei.trace_id IN (
                    SELECT id
                    FROM traces
                    LEFT JOIN fsc ON fsc.entity_id = traces.id
                    WHERE workspace_id = :workspace_id
                    AND fsc.feedback_scores_count = 0
                )
                <endif>
                <if(feedback_scores_filters)>
                AND ei.trace_id IN (
                    SELECT entity_id
                    FROM feedback_scores_final
                    GROUP BY entity_id, name
                    HAVING <feedback_scores_filters>
                )
                <endif>
                <if(dataset_item_filters)>
                AND ei.dataset_item_id IN (
                    SELECT div.id
                    FROM dataset_item_versions div
                    INNER JOIN experiment_items_scope ei_inner ON ei_inner.dataset_item_id = div.id
                    LEFT JOIN experiments e ON e.id = ei_inner.experiment_id AND e.workspace_id = :workspace_id
                    WHERE div.workspace_id = :workspace_id
                    AND div.dataset_id = :datasetId
                    AND div.dataset_version_id = COALESCE(nullIf(e.dataset_version_id, ''), :versionId)
                    AND <dataset_item_filters>
                    ORDER BY (div.workspace_id, div.dataset_id, div.dataset_version_id, div.id) DESC, div.last_updated_at DESC
                    LIMIT 1 BY div.id
                )
                <endif>
            ), trace_project_mapping AS (
                SELECT DISTINCT
                    id AS trace_id,
                    project_id,
                    if(end_time IS NOT NULL AND start_time IS NOT NULL
                        AND notEquals(start_time, toDateTime64('1970-01-01 00:00:00.000', 9)),
                        (dateDiff('microsecond', start_time, end_time) / 1000.0),
                        NULL) as duration
                FROM traces final
                WHERE workspace_id = :workspace_id
                AND id IN (SELECT DISTINCT trace_id FROM experiment_items_filtered WHERE trace_id IS NOT NULL)
            ), traces_with_cost_and_duration AS (
                SELECT DISTINCT
                    eif.trace_id as trace_id,
                    tpm.duration as duration,
                    s.total_estimated_cost as total_estimated_cost,
                    s.usage as usage
                FROM experiment_items_filtered eif
                INNER JOIN trace_project_mapping tpm ON tpm.trace_id = eif.trace_id
                LEFT JOIN (
                    SELECT
                        trace_id,
                        sum(total_estimated_cost) as total_estimated_cost,
                        sumMap(usage) as usage
                    FROM spans final
                    INNER JOIN trace_project_mapping tpm ON tpm.trace_id = spans.trace_id
                    WHERE workspace_id = :workspace_id
                    AND project_id = tpm.project_id
                    GROUP BY workspace_id, project_id, trace_id
                ) AS s ON eif.trace_id = s.trace_id
            ), feedback_scores_agg AS (
                SELECT
                    entity_id,
                    mapFromArrays(
                        groupArray(name),
                        groupArray(value)
                    ) AS feedback_scores
                FROM feedback_scores_final
                WHERE entity_id IN (SELECT DISTINCT trace_id FROM experiment_items_filtered WHERE trace_id IS NOT NULL)
                GROUP BY workspace_id, project_id, entity_id
            ), feedback_scores_percentiles AS (
                SELECT
                    name,
                    quantiles(0.5, 0.9, 0.99)(toFloat64(value)) AS percentiles
                FROM feedback_scores_final
                WHERE entity_id IN (SELECT DISTINCT trace_id FROM experiment_items_filtered WHERE trace_id IS NOT NULL)
                GROUP BY name
            ), usage_total_tokens_data AS (
                SELECT
                    toFloat64(tc.usage['total_tokens']) AS total_tokens
                FROM traces_with_cost_and_duration tc
                WHERE tc.usage['total_tokens'] IS NOT NULL AND tc.usage['total_tokens'] > 0
            )
            SELECT
                count(DISTINCT ei.id) as experiment_items_count,
                count(DISTINCT tc.trace_id) as trace_count,
                mapFromArrays(
                    ['p50', 'p90', 'p99'],
                    arrayMap(
                      v -> toDecimal64(
                             greatest(
                               least(if(isFinite(v), v, 0), 999999999.999999999),
                               -999999999.999999999
                             ),
                             9
                           ),
                      quantiles(0.5, 0.9, 0.99)(tc.duration)
                    )
                ) AS duration,
                avgMap(f.feedback_scores) AS feedback_scores,
                (SELECT mapFromArrays(
                    groupArray(name),
                    groupArray(mapFromArrays(
                        ['p50', 'p90', 'p99'],
                        arrayMap(v -> toDecimal64(if(isFinite(v), v, 0), 9), percentiles)
                    ))
                ) FROM feedback_scores_percentiles) AS feedback_scores_percentiles,
                avgIf(tc.total_estimated_cost, tc.total_estimated_cost > 0) AS total_estimated_cost_,
                toDecimal128(if(isNaN(total_estimated_cost_), 0, total_estimated_cost_), 12) AS total_estimated_cost_avg,
                sumIf(tc.total_estimated_cost, tc.total_estimated_cost > 0) AS total_estimated_cost_sum_,
                toDecimal128(total_estimated_cost_sum_, 12) AS total_estimated_cost_sum,
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
                      quantilesIf(0.5, 0.9, 0.99)(tc.total_estimated_cost, tc.total_estimated_cost > 0)
                    )
                ) AS total_estimated_cost_percentiles,
                avgMap(tc.usage) AS usage,
                mapFromArrays(
                    ['p50', 'p90', 'p99'],
                    arrayMap(
                      v -> toInt64(greatest(least(if(isFinite(v), v, 0), 999999999.999999999), -999999999.999999999)),
                      (SELECT quantiles(0.5, 0.9, 0.99)(total_tokens) FROM usage_total_tokens_data)
                    )
                ) AS usage_total_tokens_percentiles
            FROM experiment_items_filtered ei
            LEFT JOIN traces_with_cost_and_duration AS tc ON ei.trace_id = tc.trace_id
            LEFT JOIN feedback_scores_agg AS f ON ei.trace_id = f.entity_id
            ;
            """;

    private final @NonNull TransactionTemplateAsync asyncTemplate;
    private final @NonNull IdGenerator idGenerator;
    private final @NonNull FilterQueryBuilder filterQueryBuilder;
    private final @NonNull SortingQueryBuilder sortingQueryBuilder;
    private final @NonNull SortingFactoryDatasets sortingFactory;
    private final @NonNull OpikConfiguration config;

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
                        Set<String> tags = Optional.ofNullable(row.get("tags", String[].class))
                                .map(arr -> new HashSet<>(Arrays.asList(arr)))
                                .orElseGet(HashSet::new);
                        log.debug("Retrieved versioned item: dataset_item_id='{}', hash='{}', tags='{}'",
                                datasetItemId, hash, tags);
                        return DatasetItemIdAndHash.builder()
                                .itemId(datasetItemId)
                                .dataHash(hash)
                                .tags(tags)
                                .build();
                    }))
                    .collectList()
                    .doOnSuccess(items -> log.info("Retrieved '{}' item IDs and hashes for version '{}'", items.size(),
                            versionId))
                    .flatMapMany(Flux::fromIterable);
        });
    }

    @WithSpan
    public Flux<DatasetItem> getItems(@NonNull UUID datasetId, @NonNull UUID versionId, int limit,
            UUID lastRetrievedId) {
        log.info("Streaming dataset items by datasetId '{}', versionId '{}', limit '{}', lastRetrievedId '{}'",
                datasetId, versionId, limit, lastRetrievedId);

        ST template = TemplateUtils.newST(SELECT_DATASET_ITEM_VERSIONS);
        if (lastRetrievedId != null) {
            template.add("lastRetrievedId", true);
        }
        String query = template.render();

        return asyncTemplate.stream(connection -> {
            var statement = connection.createStatement(query)
                    .bind("datasetId", datasetId.toString())
                    .bind("versionId", versionId.toString())
                    .bind("limit", limit);

            if (lastRetrievedId != null) {
                statement.bind("lastRetrievedId", lastRetrievedId.toString());
            } else {
                statement.bind("offset", 0);
            }

            Segment segment = startSegment(DATASET_ITEM_VERSIONS, CLICKHOUSE, "stream_version_items");

            return makeFluxContextAware(bindWorkspaceIdToFlux(statement))
                    .doFinally(signalType -> endSegment(segment))
                    .flatMap(DatasetItemResultMapper::mapItem);
        });
    }

    /**
     * Helper method to add common filter conditions to a StringTemplate.
     * This encapsulates the repeated pattern of adding filters and search criteria to templates.
     *
     * @param template The StringTemplate to add filters to
     * @param criteria The search criteria containing filters and search terms
     */
    private void addFiltersToTemplate(@NonNull ST template, @NonNull DatasetItemSearchCriteria criteria) {
        // Add filters if present
        if (CollectionUtils.isNotEmpty(criteria.filters())) {
            var datasetItemFiltersOpt = FilterQueryBuilder.toAnalyticsDbFilters(criteria.filters(),
                    FilterStrategy.DATASET_ITEM);
            datasetItemFiltersOpt.ifPresent(datasetItemFilters -> template.add("dataset_item_filters",
                    datasetItemFilters));

            FilterQueryBuilder.toAnalyticsDbFilters(criteria.filters(), FilterStrategy.EXPERIMENT_ITEM)
                    .ifPresent(experimentItemFilters -> template.add("experiment_item_filters",
                            experimentItemFilters));

            FilterQueryBuilder.toAnalyticsDbFilters(criteria.filters(), FilterStrategy.FEEDBACK_SCORES)
                    .ifPresent(feedbackScoresFilters -> template.add("feedback_scores_filters",
                            feedbackScoresFilters));

            FilterQueryBuilder.toAnalyticsDbFilters(criteria.filters(), FilterStrategy.FEEDBACK_SCORES_IS_EMPTY)
                    .ifPresent(feedbackScoresEmptyFilters -> template.add("feedback_scores_empty_filters",
                            feedbackScoresEmptyFilters));
        }

        // Add search if present
        if (StringUtils.isNotBlank(criteria.search())) {
            template.add("search", true);
        }
    }

    /**
     * Adds dataset item filters to the StringTemplate if filters are present.
     *
     * @param template the StringTemplate to add filters to
     * @param filters the list of filters to apply, may be null or empty
     */
    private void addDatasetItemFiltersToTemplate(ST template, List<? extends Filter> filters) {
        if (CollectionUtils.isNotEmpty(filters)) {
            FilterQueryBuilder.toAnalyticsDbFilters(filters, FilterStrategy.DATASET_ITEM)
                    .ifPresent(datasetItemFilters -> template.add("dataset_item_filters",
                            datasetItemFilters));
        }
    }

    /**
     * Binds dataset item filter parameters to the R2DBC statement.
     *
     * @param statement the R2DBC statement to bind parameters to
     * @param filters the list of filters to bind, may be null or empty
     */
    private void bindDatasetItemFilters(Statement statement, List<? extends Filter> filters) {
        if (CollectionUtils.isNotEmpty(filters)) {
            FilterQueryBuilder.bind(statement, filters, FilterStrategy.DATASET_ITEM);
        }
    }

    /**
     * Helper method to bind search terms and filters to a statement.
     * This encapsulates the repeated pattern of binding search and filter parameters.
     *
     * @param statement The R2DBC statement to bind parameters to
     * @param criteria The search criteria containing search terms and filters
     * @return The statement with all parameters bound
     */
    private Statement bindSearchAndFilters(@NonNull Statement statement, @NonNull DatasetItemSearchCriteria criteria) {
        // Bind search terms if present
        if (StringUtils.isNotBlank(criteria.search())) {
            statement = filterQueryBuilder.bindSearchTerms(statement, criteria.search());
        }

        // Bind filter parameters if present
        if (CollectionUtils.isNotEmpty(criteria.filters())) {
            statement = FilterQueryBuilder.bind(statement, criteria.filters(), FilterStrategy.DATASET_ITEM);
            statement = FilterQueryBuilder.bind(statement, criteria.filters(), FilterStrategy.EXPERIMENT_ITEM);
            statement = FilterQueryBuilder.bind(statement, criteria.filters(), FilterStrategy.FEEDBACK_SCORES);
            statement = FilterQueryBuilder.bind(statement, criteria.filters(), FilterStrategy.FEEDBACK_SCORES_IS_EMPTY);
        }

        return statement;
    }

    @Override
    @WithSpan
    public Mono<DatasetItemPage> getItems(@NonNull DatasetItemSearchCriteria criteria, int page, int size,
            @NonNull UUID versionId) {
        return Mono.zip(
                getCount(criteria, versionId),
                getColumns(criteria.datasetId(), versionId)).flatMap(tuple -> {
                    Long total = tuple.getT1();
                    Set<Column> columns = tuple.getT2();

                    return asyncTemplate.nonTransaction(connection -> {
                        // Build template with filters and truncation
                        ST template = TemplateUtils.newST(SELECT_DATASET_ITEM_VERSIONS);
                        template = ImageUtils.addTruncateToTemplate(template, criteria.truncate());
                        template.add("truncationSize", config.getResponseFormatting().getTruncationSize());
                        addDatasetItemFiltersToTemplate(template, criteria.filters());

                        var statement = connection.createStatement(template.render())
                                .bind("datasetId", criteria.datasetId().toString())
                                .bind("versionId", versionId.toString())
                                .bind("limit", size)
                                .bind("offset", (page - 1) * size);

                        // Bind filter parameters
                        bindDatasetItemFilters(statement, criteria.filters());

                        Segment segment = startSegment(DATASET_ITEM_VERSIONS, CLICKHOUSE,
                                "select_dataset_item_versions");

                        return makeFluxContextAware(bindWorkspaceIdToFlux(statement))
                                .doFinally(signalType -> endSegment(segment))
                                .flatMap(DatasetItemResultMapper::mapItem)
                                .collectList()
                                .map(items -> new DatasetItemPage(items, page, items.size(), total, columns,
                                        sortingFactory.getSortableFields()));
                    });
                });
    }

    @Override
    public Mono<DatasetItemPage> getItemsWithExperimentItems(@NonNull DatasetItemSearchCriteria criteria, int page,
            int size, @NonNull UUID versionId) {
        log.info(
                "Getting versioned dataset items with experiment items for dataset '{}', version '{}', experiments '{}'",
                criteria.datasetId(), versionId, criteria.experimentIds());

        return Mono.deferContextual(ctx -> {
            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);

            return asyncTemplate.nonTransaction(connection -> {
                // Build the query using StringTemplate
                ST template = TemplateUtils.newST(SELECT_DATASET_ITEM_VERSIONS_WITH_EXPERIMENT_ITEMS);

                template = ImageUtils.addTruncateToTemplate(template, criteria.truncate());
                template.add("truncationSize", config.getResponseFormatting().getTruncationSize());

                // Add experiment IDs to template
                if (CollectionUtils.isNotEmpty(criteria.experimentIds())) {
                    template.add("experiment_ids", criteria.experimentIds());
                }

                // Add filters and search criteria using helper method
                addFiltersToTemplate(template, criteria);

                // Add sorting if present
                var hasDynamicKeys = criteria.sortingFields() != null
                        && sortingQueryBuilder.hasDynamicKeys(criteria.sortingFields());

                if (criteria.sortingFields() != null && !criteria.sortingFields().isEmpty()) {
                    String sortingQuery = sortingQueryBuilder.toOrderBySql(
                            criteria.sortingFields(),
                            filterQueryBuilder.buildDatasetItemFieldMapping(criteria.sortingFields()));
                    if (sortingQuery != null) {
                        template.add("sorting", sortingQuery);
                    }
                }

                String query = template.render();

                var statement = connection.createStatement(query)
                        .bind("workspace_id", workspaceId)
                        .bind("datasetId", criteria.datasetId().toString())
                        .bind("versionId", versionId.toString())
                        .bind("limit", size)
                        .bind("offset", (page - 1) * size);

                // Bind experiment IDs as array
                if (criteria.experimentIds() != null && !criteria.experimentIds().isEmpty()) {
                    statement.bind("experiment_ids", criteria.experimentIds().toArray(UUID[]::new));
                }

                // Bind dynamic sorting keys if present
                if (hasDynamicKeys) {
                    statement = sortingQueryBuilder.bindDynamicKeys(statement, criteria.sortingFields());
                }

                // Bind search and filter parameters using helper method
                statement = bindSearchAndFilters(statement, criteria);

                Segment segment = startSegment(DATASET_ITEM_VERSIONS, CLICKHOUSE,
                        "select_dataset_item_versions_with_experiment_items");

                return makeFluxContextAware(bindWorkspaceIdToFlux(statement))
                        .doFinally(signalType -> endSegment(segment))
                        .flatMap(DatasetItemResultMapper::mapItem)
                        .collectList()
                        .zipWith(getCountWithExperimentFilters(criteria, versionId))
                        .zipWith(getColumns(criteria.datasetId(), versionId))
                        .map(tuple -> {
                            var itemsAndCount = tuple.getT1();
                            List<DatasetItem> items = itemsAndCount.getT1();
                            Long count = itemsAndCount.getT2();
                            Set<Column> columns = tuple.getT2();

                            return new DatasetItemPage(items, page, items.size(), count, columns,
                                    sortingFactory.getSortableFields());
                        });
            });
        });
    }

    @Override
    public Mono<List<Column>> getExperimentItemsOutputColumns(@NonNull UUID datasetId, Set<UUID> experimentIds) {
        log.debug("Getting experiment items output columns for dataset '{}'", datasetId);

        return Mono.deferContextual(ctx -> {
            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);

            return asyncTemplate.nonTransaction(connection -> {
                ST template = TemplateUtils.newST(SELECT_EXPERIMENT_ITEMS_OUTPUT_COLUMNS);

                if (CollectionUtils.isNotEmpty(experimentIds)) {
                    template.add("experiment_ids", true);
                }

                var statement = connection.createStatement(template.render())
                        .bind("workspace_id", workspaceId)
                        .bind("datasetId", datasetId);

                if (CollectionUtils.isNotEmpty(experimentIds)) {
                    statement.bind("experiment_ids", experimentIds.toArray(UUID[]::new));
                }

                Segment segment = startSegment(DATASET_ITEM_VERSIONS, CLICKHOUSE,
                        "get_experiment_items_output_columns");

                return Flux.from(statement.execute())
                        .doFinally(signalType -> endSegment(segment))
                        .flatMap(result -> DatasetItemResultMapper.mapColumns(result, "output"))
                        .next()
                        .map(List::copyOf)
                        .defaultIfEmpty(List.of());
            });
        });
    }

    private Mono<Long> getCountWithExperimentFilters(@NonNull DatasetItemSearchCriteria criteria,
            @NonNull UUID versionId) {
        log.debug("Getting filtered count for dataset '{}' version '{}' with experiment filters", criteria.datasetId(),
                versionId);

        return asyncTemplate.nonTransaction(connection -> {
            ST template = TemplateUtils.newST(SELECT_DATASET_ITEM_VERSIONS_WITH_EXPERIMENT_ITEMS_COUNT);

            template = ImageUtils.addTruncateToTemplate(template, criteria.truncate());
            template.add("truncationSize", config.getResponseFormatting().getTruncationSize());

            // Add experiment IDs if present
            if (CollectionUtils.isNotEmpty(criteria.experimentIds())) {
                template.add("experiment_ids", true);
            }

            // Add filters and search criteria using helper method
            addFiltersToTemplate(template, criteria);

            var statement = connection.createStatement(template.render())
                    .bind("datasetId", criteria.datasetId())
                    .bind("versionId", versionId.toString());

            if (CollectionUtils.isNotEmpty(criteria.experimentIds())) {
                statement.bind("experiment_ids", criteria.experimentIds().toArray(UUID[]::new));
            }

            // Bind search and filter parameters using helper method
            statement = bindSearchAndFilters(statement, criteria);

            Segment segment = startSegment(DATASET_ITEM_VERSIONS, CLICKHOUSE,
                    "count_dataset_item_versions_with_experiment_filters");

            return makeFluxContextAware(bindWorkspaceIdToFlux(statement))
                    .doFinally(signalType -> endSegment(segment))
                    .flatMap(result -> result.map((row, meta) -> row.get("count", Long.class)))
                    .reduce(0L, Long::sum);
        });
    }

    private Mono<Long> getCount(DatasetItemSearchCriteria criteria, UUID versionId) {
        return asyncTemplate.nonTransaction(connection -> {
            // Build template with filters
            ST template = TemplateUtils.newST(SELECT_DATASET_ITEM_VERSIONS_COUNT);
            addDatasetItemFiltersToTemplate(template, criteria.filters());

            var statement = connection.createStatement(template.render())
                    .bind("datasetId", criteria.datasetId().toString())
                    .bind("versionId", versionId.toString());

            // Bind filter parameters
            bindDatasetItemFilters(statement, criteria.filters());

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
            @NonNull List<UUID> unchangedUuids) {

        log.info("Applying delta for dataset '{}': baseVersion='{}', newVersion='{}', " +
                "added='{}', edited='{}', deleted='{}'",
                datasetId, baseVersionId, newVersionId, addedItems.size(), editedItems.size(),
                deletedIds.size());

        // Collect all stable item IDs that are being edited (so we don't copy them from base)
        Set<UUID> editedItemIds = editedItems.stream()
                .map(DatasetItem::datasetItemId)
                .collect(Collectors.toSet());

        // Combine deleted and edited IDs for exclusion when copying
        Set<UUID> excludedIds = new HashSet<>(deletedIds);
        excludedIds.addAll(editedItemIds);

        return Mono.deferContextual(ctx -> {
            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);
            String userName = ctx.get(RequestContext.USER_NAME);

            // Step 1: Insert added items (will sort first due to latest/largest UUIDs)
            Mono<Long> insertAdded = insertItems(datasetId, newVersionId, addedItems, workspaceId, userName);

            // Step 2: Insert edited items (will sort after added due to middle UUIDs)
            Mono<Long> insertEdited = insertItems(datasetId, newVersionId, editedItems, workspaceId, userName);

            // Step 3: Copy unchanged items (will sort last due to earliest/smallest UUIDs)
            Mono<Long> copyUnchanged = copyUnchangedItems(datasetId, baseVersionId, newVersionId,
                    excludedIds, unchangedUuids, workspaceId, userName);

            // Execute all operations and sum the results
            return insertAdded
                    .zipWith(insertEdited, Long::sum)
                    .zipWith(copyUnchanged, Long::sum)
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
            @NonNull UUID newVersionId, @NonNull DatasetItemBatchUpdate batchUpdate, @NonNull List<UUID> uuids) {

        // Early return ONLY if IDs are explicitly empty AND filters are null (not provided at all)
        // Note: empty filters list means "select all items", so we should NOT early return in that case
        boolean hasIds = CollectionUtils.isNotEmpty(batchUpdate.ids());
        boolean hasFilters = batchUpdate.filters() != null; // null means not provided, empty list means "select all"

        if (!hasIds && !hasFilters) {
            // Neither IDs nor filters provided - nothing to update
            return Mono.just(0L);
        }

        log.info(
                "Batch updating items in dataset '{}' from version '{}' to version '{}', idsSize='{}', filtersSize='{}'",
                datasetId, baseVersionId, newVersionId,
                batchUpdate.ids() != null ? batchUpdate.ids().size() : 0,
                batchUpdate.filters() != null ? batchUpdate.filters().size() : 0);

        return Mono.deferContextual(ctx -> {
            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);
            String userName = ctx.get(RequestContext.USER_NAME);

            return asyncTemplate.nonTransaction(connection -> {
                // Build query using StringTemplate for conditional fields
                ST template = new ST(BATCH_UPDATE_ITEMS);

                // Add conditional parameters based on what fields are being updated
                if (batchUpdate.update().data() != null) {
                    template.add("data", true);
                }
                if (batchUpdate.update().tags() != null) {
                    template.add("tags", true);
                    if (Boolean.TRUE.equals(batchUpdate.mergeTags())) {
                        template.add("merge_tags", true);
                    }
                }

                // Add either item IDs or filters based on what's provided
                if (batchUpdate.ids() != null && !batchUpdate.ids().isEmpty()) {
                    template.add("item_ids", true);
                } else if (batchUpdate.filters() != null && !batchUpdate.filters().isEmpty()) {
                    FilterQueryBuilder.toAnalyticsDbFilters(batchUpdate.filters(), FilterStrategy.DATASET_ITEM)
                            .ifPresent(datasetItemFilters -> template.add("dataset_item_filters", datasetItemFilters));
                }

                String query = template.render();

                // Convert UUIDs to strings for ClickHouse
                String[] uuidStrings = uuids.stream()
                        .map(UUID::toString)
                        .toArray(String[]::new);

                var statement = connection.createStatement(query)
                        .bind("workspace_id", workspaceId)
                        .bind("datasetId", datasetId.toString())
                        .bind("baseVersionId", baseVersionId.toString())
                        .bind("newVersionId", newVersionId.toString())
                        .bind("uuids", uuidStrings)
                        .bind("userName", userName);

                // Bind item IDs if provided
                if (batchUpdate.ids() != null && !batchUpdate.ids().isEmpty()) {
                    String[] itemIdStrings = batchUpdate.ids().stream()
                            .map(UUID::toString)
                            .toArray(String[]::new);
                    statement.bind("itemIds", itemIdStrings);
                }

                // Bind filter parameters if provided
                if (batchUpdate.filters() != null && !batchUpdate.filters().isEmpty()) {
                    FilterQueryBuilder.bind(statement, batchUpdate.filters(), FilterStrategy.DATASET_ITEM);
                }

                // Bind optional update fields
                if (batchUpdate.update().data() != null) {
                    Map<String, String> dataAsStrings = DatasetItemResultMapper
                            .getOrDefault(batchUpdate.update().data());
                    statement.bind("data", dataAsStrings);
                }
                if (batchUpdate.update().tags() != null) {
                    statement.bind("tags", batchUpdate.update().tags().toArray(new String[0]));
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

            // Build batch insert query using template
            List<TemplateUtils.QueryItem> queryItems = TemplateUtils.getQueryItemPlaceHolder(items.size());
            var template = TemplateUtils.newST(BATCH_INSERT_ITEMS)
                    .add("items", queryItems);

            var statement = connection.createStatement(template.render())
                    .bind("dataset_id", datasetId.toString())
                    .bind("dataset_version_id", newVersionId.toString())
                    .bind("created_by", userName)
                    .bind("last_updated_by", userName)
                    .bind("workspace_id", workspaceId);

            // Bind all item-specific parameters
            int i = 0;
            for (DatasetItem item : items) {
                UUID stableItemId = item.datasetItemId();
                Map<String, String> dataAsStrings = DatasetItemResultMapper.getOrDefault(item.data());

                statement
                        .bind("id" + i, item.id().toString())
                        .bind("dataset_item_id" + i, stableItemId.toString())
                        .bind("data" + i, dataAsStrings)
                        .bind("metadata" + i, "")
                        .bind("source" + i, item.source() != null ? item.source().getValue() : "sdk")
                        .bind("trace_id" + i, DatasetItemResultMapper.getOrDefault(item.traceId()))
                        .bind("span_id" + i, DatasetItemResultMapper.getOrDefault(item.spanId()))
                        .bind("tags" + i, item.tags() != null ? item.tags().toArray(new String[0]) : new String[0])
                        .bind("item_created_at" + i, formatTimestamp(item.createdAt()))
                        .bind("item_last_updated_at" + i, formatTimestamp(item.lastUpdatedAt()))
                        .bind("item_created_by" + i, item.createdBy() != null ? item.createdBy() : userName)
                        .bind("item_last_updated_by" + i,
                                item.lastUpdatedBy() != null ? item.lastUpdatedBy() : userName);

                i++;
            }

            return Flux.from(statement.execute())
                    .flatMap(Result::getRowsUpdated)
                    .reduce(0L, Long::sum)
                    .map(results -> itemCount) // Return item count instead of sum of results
                    .doOnSuccess(count -> log.debug("Inserted '{}' items in batch", count))
                    .doOnError(e -> log.error("Batch insert items failed for dataset '{}', version '{}'",
                            datasetId, newVersionId, e))
                    .doFinally(signalType -> endSegment(segment));
        });
    }

    @Override
    @WithSpan
    public Mono<Long> removeItemsFromVersion(@NonNull UUID datasetId, @NonNull UUID versionId,
            @NonNull Set<UUID> itemIds, @NonNull String workspaceId) {

        if (itemIds.isEmpty()) {
            return Mono.just(0L);
        }

        log.info("Removing '{}' items from version '{}' for dataset '{}'", itemIds.size(), versionId, datasetId);

        return asyncTemplate.nonTransaction(connection -> {
            Segment segment = startSegment(DATASET_ITEM_VERSIONS, CLICKHOUSE, "remove_items_from_version");

            // First, count how many items actually exist (to handle non-existent IDs)
            return countItemsByIds(datasetId, versionId, itemIds, workspaceId)
                    .flatMap(existingCount -> {
                        if (existingCount == 0) {
                            log.info("No items found to delete for version '{}'", versionId);
                            return Mono.just(0L);
                        }

                        // Use StringTemplate to generate query with item_ids condition
                        var template = new ST(DELETE_ITEMS_FROM_VERSION);
                        template.add("item_ids", true); // Enable item_ids condition
                        String deleteQuery = template.render();

                        var statement = connection.createStatement(deleteQuery)
                                .bind("dataset_id", datasetId.toString())
                                .bind("version_id", versionId.toString())
                                .bind("workspace_id", workspaceId);

                        // Bind the item IDs array
                        String[] itemIdStrings = itemIds.stream()
                                .map(UUID::toString)
                                .toArray(String[]::new);
                        statement.bind("item_ids", itemIdStrings);

                        // delete async and returns 0, so return the count we calculated
                        return Flux.from(statement.execute())
                                .flatMap(Result::getRowsUpdated)
                                .reduce(0L, Long::sum)
                                .map(results -> existingCount) // Return the actual count of existing items
                                .doOnSuccess(count -> log.info(
                                        "Removed '{}' items from version '{}' (requested '{}' IDs, '{}' existed)",
                                        count, versionId, itemIds.size(), existingCount))
                                .doOnError(e -> log.error("Failed to remove items from version '{}' for dataset '{}'",
                                        versionId, datasetId, e));
                    })
                    .doFinally(signalType -> endSegment(segment));
        });
    }

    @Override
    @WithSpan
    public Mono<Long> removeItemsFromVersionByFilters(@NonNull UUID datasetId, @NonNull UUID versionId,
            @NonNull List<DatasetItemFilter> filters, @NonNull String workspaceId) {

        // Empty filter list means "delete all" (no filters = match everything)
        log.info("Removing items from version '{}' for dataset '{}' using '{}' filters (empty = delete all)",
                versionId, datasetId, filters != null ? filters.size() : 0);

        return asyncTemplate.nonTransaction(connection -> {
            Segment segment = startSegment(DATASET_ITEM_VERSIONS, CLICKHOUSE,
                    "remove_items_from_version_by_filters");

            // First, count how many items will be deleted
            return countItemsMatchingFilters(datasetId, versionId, filters, workspaceId)
                    .flatMap(deletedCount -> {
                        if (deletedCount == 0) {
                            log.info("No items match filters for version '{}'", versionId);
                            return Mono.just(0L);
                        }

                        // Build the filter query using StringTemplate
                        // Empty filters means "delete all" - no filter conditions
                        Optional<String> filterConditionsOpt = CollectionUtils.isEmpty(filters)
                                ? Optional.empty()
                                : FilterQueryBuilder.toAnalyticsDbFilters(filters, FilterStrategy.DATASET_ITEM);

                        // Use StringTemplate to generate query with optional filter conditions
                        var template = new ST(DELETE_ITEMS_FROM_VERSION);
                        filterConditionsOpt.ifPresent(filterConditions -> template.add("dataset_item_filters",
                                filterConditions));
                        String deleteQuery = template.render();

                        var statement = connection.createStatement(deleteQuery)
                                .bind("dataset_id", datasetId.toString())
                                .bind("version_id", versionId.toString())
                                .bind("workspace_id", workspaceId);

                        // Bind filter parameters using FilterQueryBuilder (only if filters exist)
                        if (CollectionUtils.isNotEmpty(filters)) {
                            statement = FilterQueryBuilder.bind(statement, filters, FilterStrategy.DATASET_ITEM);
                        }

                        return Flux.from(statement.execute())
                                .flatMap(Result::getRowsUpdated)
                                .reduce(0L, Long::sum)
                                .map(results -> deletedCount) // Return the count we calculated earlier
                                .doOnSuccess(
                                        count -> log.info("Removed '{}' items from version '{}'", count, versionId))
                                .doOnError(e -> log.error(
                                        "Failed to remove items from version '{}' for dataset '{}' using filters",
                                        versionId, datasetId, e));
                    })
                    .doFinally(signalType -> endSegment(segment));
        });
    }

    /**
     * Counts items matching the given filters in a specific version.
     * Used to determine how many items will be deleted before performing the deletion.
     */
    private Mono<Long> countItemsMatchingFilters(UUID datasetId, UUID versionId, List<DatasetItemFilter> filters,
            String workspaceId) {

        return asyncTemplate.nonTransaction(connection -> {
            // Empty filters means "count all" - no filter conditions
            Optional<String> filterConditionsOpt = CollectionUtils.isEmpty(filters)
                    ? Optional.empty()
                    : FilterQueryBuilder.toAnalyticsDbFilters(filters, FilterStrategy.DATASET_ITEM);

            // Use StringTemplate to generate query with optional filter conditions
            var template = new ST(COUNT_ITEMS);
            filterConditionsOpt.ifPresent(filterConditions -> template.add("dataset_item_filters", filterConditions));
            String countQuery = template.render();

            var statement = connection.createStatement(countQuery)
                    .bind("dataset_id", datasetId.toString())
                    .bind("version_id", versionId.toString())
                    .bind("workspace_id", workspaceId);

            // Bind filter parameters (only if filters exist)
            if (CollectionUtils.isNotEmpty(filters)) {
                statement = FilterQueryBuilder.bind(statement, filters, FilterStrategy.DATASET_ITEM);
            }

            return Flux.from(statement.execute())
                    .flatMap(result -> result.map((row, metadata) -> row.get("count", Long.class)))
                    .next()
                    .defaultIfEmpty(0L);
        });
    }

    /**
     * Counts items by their IDs in a specific version.
     * Used to determine how many of the requested IDs actually exist before deletion.
     */
    private Mono<Long> countItemsByIds(UUID datasetId, UUID versionId, Set<UUID> itemIds, String workspaceId) {
        return asyncTemplate.nonTransaction(connection -> {
            var template = new ST(COUNT_ITEMS);
            template.add("item_ids", true); // Enable item_ids condition
            String countQuery = template.render();

            var statement = connection.createStatement(countQuery)
                    .bind("dataset_id", datasetId.toString())
                    .bind("version_id", versionId.toString())
                    .bind("workspace_id", workspaceId)
                    .bind("item_ids", itemIds.toArray(UUID[]::new));

            return Flux.from(statement.execute())
                    .flatMap(result -> result.map((row, metadata) -> row.get("count", Long.class)))
                    .next()
                    .defaultIfEmpty(0L);
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

        return asyncTemplate.stream(connection -> {
            var statement = connection.createStatement(SELECT_ITEMS_BY_DATASET_ITEM_IDS)
                    .bind("datasetId", datasetId.toString())
                    .bind("versionId", versionId.toString())
                    .bind("datasetItemIds", datasetItemIds.toArray(UUID[]::new));

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
                .datasetItemId(UUID.fromString(row.get("dataset_item_id", String.class)))
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

        return asyncTemplate.stream(connection -> {
            var statement = connection.createStatement(SELECT_ITEM_ID_MAPPING_BY_ROW_IDS)
                    .bind("rowIds", rowIds.toArray(UUID[]::new));

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

    @Override
    @WithSpan
    public Mono<List<WorkspaceAndResourceId>> getDatasetItemWorkspace(@NonNull Set<UUID> datasetItemRowIds) {
        if (datasetItemRowIds.isEmpty()) {
            return Mono.just(List.of());
        }

        log.debug("Getting workspace IDs for '{}' dataset item row IDs", datasetItemRowIds.size());

        return asyncTemplate.nonTransaction(connection -> {
            var statement = connection.createStatement(SELECT_DATASET_WORKSPACE_ITEMS_BY_ROW_IDS)
                    .bind("datasetItemRowIds", datasetItemRowIds.toArray(UUID[]::new));

            Segment segment = startSegment(DATASET_ITEM_VERSIONS, CLICKHOUSE, "get_dataset_item_workspace");

            return Flux.from(statement.execute())
                    .flatMap(result -> result.map((row, rowMetadata) -> new WorkspaceAndResourceId(
                            row.get("workspace_id", String.class),
                            UUID.fromString(row.get("id", String.class)))))
                    .collectList()
                    .doFinally(signalType -> endSegment(segment));
        });
    }

    @Override
    @WithSpan
    public Mono<ProjectStats> getExperimentItemsStats(
            @NonNull UUID datasetId,
            @NonNull UUID versionId,
            @NonNull Set<UUID> experimentIds,
            List<ExperimentsComparisonFilter> filters) {
        log.info("Getting experiment items stats for dataset '{}', version '{}', experiments '{}' with filters '{}'",
                datasetId, versionId, experimentIds, filters);

        var template = TemplateUtils.newST(SELECT_DATASET_ITEM_VERSIONS_WITH_EXPERIMENT_ITEMS_STATS);
        template.add("dataset_id", datasetId);
        template.add("version_id", versionId);
        if (!experimentIds.isEmpty()) {
            template.add("experiment_ids", true);
        }

        applyFiltersToTemplate(template, filters);

        String sql = template.render();
        log.debug("Experiment items stats query: '{}'", sql);

        return asyncTemplate.nonTransaction(connection -> {
            Statement statement = connection.createStatement(sql);
            bindStatementParameters(statement, datasetId, versionId, experimentIds, filters);

            return makeFluxContextAware(bindWorkspaceIdToFlux(statement))
                    .flatMap(result -> result.map(
                            (row, rowMetadata) -> com.comet.opik.domain.stats.StatsMapper.mapExperimentItemsStats(row)))
                    .singleOrEmpty();
        })
                .doOnError(error -> log.error("Failed to get experiment items stats", error));
    }

    private void applyFiltersToTemplate(ST template, List<ExperimentsComparisonFilter> filters) {
        Optional.ofNullable(filters)
                .ifPresent(filtersParam -> {
                    FilterQueryBuilder.toAnalyticsDbFilters(filtersParam,
                            com.comet.opik.domain.filter.FilterStrategy.EXPERIMENT_ITEM)
                            .ifPresent(experimentItemFilters -> template.add("experiment_item_filters",
                                    experimentItemFilters));

                    FilterQueryBuilder.toAnalyticsDbFilters(filtersParam,
                            com.comet.opik.domain.filter.FilterStrategy.FEEDBACK_SCORES)
                            .ifPresent(feedbackScoresFilters -> template.add("feedback_scores_filters",
                                    feedbackScoresFilters));

                    FilterQueryBuilder.toAnalyticsDbFilters(filtersParam,
                            com.comet.opik.domain.filter.FilterStrategy.FEEDBACK_SCORES_IS_EMPTY)
                            .ifPresent(feedbackScoresEmptyFilters -> template.add("feedback_scores_empty_filters",
                                    feedbackScoresEmptyFilters));

                    FilterQueryBuilder.toAnalyticsDbFilters(filtersParam,
                            com.comet.opik.domain.filter.FilterStrategy.DATASET_ITEM)
                            .ifPresent(datasetItemFilters -> template.add("dataset_item_filters",
                                    datasetItemFilters));
                });
    }

    private void bindStatementParameters(Statement statement, UUID datasetId, UUID versionId, Set<UUID> experimentIds,
            List<ExperimentsComparisonFilter> filters) {
        statement.bind("datasetId", datasetId);
        statement.bind("versionId", versionId.toString());
        if (!experimentIds.isEmpty()) {
            statement.bind("experiment_ids", experimentIds.toArray(UUID[]::new));
        }

        Optional.ofNullable(filters)
                .ifPresent(filtersParam -> {
                    // Bind all filters - the builder will handle both regular and aggregated filters
                    FilterQueryBuilder.bind(statement, filtersParam,
                            com.comet.opik.domain.filter.FilterStrategy.EXPERIMENT_ITEM);
                    FilterQueryBuilder.bind(statement, filtersParam,
                            com.comet.opik.domain.filter.FilterStrategy.FEEDBACK_SCORES);
                    FilterQueryBuilder.bind(statement, filtersParam,
                            com.comet.opik.domain.filter.FilterStrategy.FEEDBACK_SCORES_IS_EMPTY);
                    FilterQueryBuilder.bind(statement, filtersParam,
                            com.comet.opik.domain.filter.FilterStrategy.DATASET_ITEM);
                });
    }

}

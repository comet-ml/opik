package com.comet.opik.domain;

import com.comet.opik.api.DatasetVersion;
import com.comet.opik.api.RecentActivity;
import com.comet.opik.infrastructure.db.EvaluatorItemListColumnMapper;
import com.comet.opik.infrastructure.db.ExecutionPolicyColumnMapper;
import com.comet.opik.infrastructure.db.MapFlatArgumentFactory;
import com.comet.opik.infrastructure.db.SequencedSetColumnMapper;
import com.comet.opik.infrastructure.db.UUIDArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterColumnMapper;
import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindList;
import org.jdbi.v3.sqlobject.customizer.BindMethods;
import org.jdbi.v3.sqlobject.statement.SqlBatch;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@RegisterArgumentFactory(UUIDArgumentFactory.class)
@RegisterArgumentFactory(MapFlatArgumentFactory.class)
@RegisterArgumentFactory(EvaluatorItemListColumnMapper.class)
@RegisterArgumentFactory(ExecutionPolicyColumnMapper.class)
@RegisterColumnMapper(MapFlatArgumentFactory.class)
@RegisterColumnMapper(SequencedSetColumnMapper.class)
@RegisterColumnMapper(EvaluatorItemListColumnMapper.class)
@RegisterColumnMapper(ExecutionPolicyColumnMapper.class)
@RegisterConstructorMapper(DatasetVersion.class)
public interface DatasetVersionDAO {

    /**
     * Sentinel written by Liquibase migration {@code 000046} for versions whose {@code items_total} has not been
     * backfilled yet. {@link #findVersionsNeedingItemsTotalMigration} selects on it, and {@link #incrementCounts}
     * refuses to do arithmetic on it — adding a delta to {@code -1} would both corrupt the counter and hide the row
     * from the backfill forever.
     */
    int ITEMS_TOTAL_NOT_MIGRATED = -1;

    @SqlUpdate("""
            INSERT INTO dataset_versions (
                id, dataset_id, version_hash, items_total, items_added, items_modified, items_deleted,
                change_description, metadata, evaluators, execution_policy,
                created_by, last_updated_by, workspace_id
            ) VALUES (
                :version.id, :version.datasetId, :version.versionHash,
                :version.itemsTotal, :version.itemsAdded, :version.itemsModified, :version.itemsDeleted,
                :version.changeDescription, :version.metadata, :version.evaluators, :version.executionPolicy,
                :version.createdBy, :version.lastUpdatedBy, :workspace_id
            )
            """)
    void insert(@BindMethods("version") DatasetVersion version, @Bind("workspace_id") String workspaceId);

    @SqlUpdate("""
            INSERT INTO dataset_versions (
                id, dataset_id, version_hash, items_total, items_added, items_modified, items_deleted,
                change_description, metadata, evaluators, execution_policy,
                created_by, last_updated_by, workspace_id
            )
            SELECT
                :version.id, :version.datasetId, :version.versionHash,
                :version.itemsTotal, :version.itemsAdded, :version.itemsModified, :version.itemsDeleted,
                :version.changeDescription, :version.metadata,
                COALESCE(:version.evaluators, base.evaluators),
                IF(:clear_execution_policy, NULL, COALESCE(:version.executionPolicy, base.execution_policy)),
                :version.createdBy, :version.lastUpdatedBy, :workspace_id
            FROM (SELECT 1) AS dummy
            LEFT JOIN dataset_versions base ON base.id = :base_version_id AND base.workspace_id = :workspace_id
            """)
    void insertWithBaseVersion(@BindMethods("version") DatasetVersion version,
            @Bind("base_version_id") UUID baseVersionId,
            @Bind("clear_execution_policy") boolean clearExecutionPolicy,
            @Bind("workspace_id") String workspaceId);

    @SqlQuery("""
            WITH target_dataset AS (
                SELECT dataset_id
                FROM dataset_versions
                WHERE id = :id AND workspace_id = :workspace_id
            ),
            version_sequences AS (
                SELECT
                    dv.id,
                    ROW_NUMBER() OVER (PARTITION BY dv.dataset_id ORDER BY dv.id) AS seq_num
                FROM dataset_versions dv
                INNER JOIN target_dataset td ON dv.dataset_id = td.dataset_id
                WHERE dv.workspace_id = :workspace_id
            )
            SELECT
                dv.id,
                dv.dataset_id,
                dv.version_hash,
                CONCAT('v', vs.seq_num) AS version_name,
                dv.items_total,
                dv.items_added,
                dv.items_modified,
                dv.items_deleted,
                dv.change_description,
                dv.metadata,
                dv.evaluators,
                dv.execution_policy,
                dv.created_at,
                dv.created_by,
                dv.last_updated_at,
                dv.last_updated_by,
                COALESCE(t.tags, JSON_ARRAY()) AS tags,
                COALESCE(JSON_CONTAINS(t.tags, '"latest"'), false) AS is_latest
            FROM dataset_versions AS dv
            INNER JOIN version_sequences vs ON dv.id = vs.id
            LEFT JOIN (
                SELECT version_id, JSON_ARRAYAGG(tag) AS tags
                FROM dataset_version_tags
                WHERE version_id = :id
                GROUP BY version_id
            ) AS t ON t.version_id = dv.id
            WHERE dv.id = :id AND dv.workspace_id = :workspace_id
            """)
    Optional<DatasetVersion> findById(@Bind("id") UUID id, @Bind("workspace_id") String workspaceId);

    @SqlQuery("""
            WITH version_sequences AS (
                SELECT
                    id,
                    ROW_NUMBER() OVER (PARTITION BY dataset_id ORDER BY id) AS seq_num
                FROM dataset_versions
                WHERE workspace_id = :workspace_id AND dataset_id = :dataset_id
            )
            SELECT
                dv.id,
                dv.dataset_id,
                dv.version_hash,
                CONCAT('v', vs.seq_num) AS version_name,
                dv.items_total,
                dv.items_added,
                dv.items_modified,
                dv.items_deleted,
                dv.change_description,
                dv.metadata,
                dv.evaluators,
                dv.execution_policy,
                dv.created_at,
                dv.created_by,
                dv.last_updated_at,
                dv.last_updated_by,
                COALESCE(t.tags, JSON_ARRAY()) AS tags,
                COALESCE(JSON_CONTAINS(t.tags, '"latest"'), false) AS is_latest
            FROM dataset_versions AS dv
            INNER JOIN version_sequences vs ON dv.id = vs.id
            LEFT JOIN (
                SELECT version_id, JSON_ARRAYAGG(tag) AS tags
                FROM dataset_version_tags
                WHERE version_id in (select id from version_sequences)
                GROUP BY version_id
            ) AS t ON t.version_id = dv.id
            WHERE dv.batch_group_id = :batch_group_id
                AND dv.dataset_id = :dataset_id
                AND dv.workspace_id = :workspace_id
            ORDER BY dv.id DESC
            LIMIT 1
            """)
    Optional<DatasetVersion> findLatestByBatchGroupId(@Bind("batch_group_id") UUID batchGroupId,
            @Bind("dataset_id") UUID datasetId,
            @Bind("workspace_id") String workspaceId);

    @SqlQuery("""
            WITH version_sequences AS (
                SELECT
                    id,
                    ROW_NUMBER() OVER (PARTITION BY dataset_id ORDER BY id) AS seq_num
                FROM dataset_versions
                WHERE workspace_id = :workspace_id AND dataset_id = :dataset_id
            )
            SELECT
                dv.id,
                dv.dataset_id,
                dv.version_hash,
                CONCAT('v', vs.seq_num) AS version_name,
                dv.items_total,
                dv.items_added,
                dv.items_modified,
                dv.items_deleted,
                dv.change_description,
                dv.metadata,
                dv.evaluators,
                dv.execution_policy,
                dv.created_at,
                dv.created_by,
                dv.last_updated_at,
                dv.last_updated_by,
                COALESCE(t.tags, JSON_ARRAY()) AS tags,
                COALESCE(JSON_CONTAINS(t.tags, '"latest"'), false) AS is_latest
            FROM dataset_versions AS dv
            INNER JOIN version_sequences vs ON dv.id = vs.id
            LEFT JOIN (
                SELECT version_id, JSON_ARRAYAGG(tag) AS tags
                FROM dataset_version_tags
                WHERE version_id in (select id from version_sequences)
                GROUP BY version_id
            ) AS t ON t.version_id = dv.id
            WHERE dv.dataset_id = :dataset_id
                AND dv.version_hash = :version_hash
                AND dv.workspace_id = :workspace_id
            """)
    Optional<DatasetVersion> findByHash(@Bind("dataset_id") UUID datasetId, @Bind("version_hash") String versionHash,
            @Bind("workspace_id") String workspaceId);

    @SqlQuery("""
            SELECT id
            FROM dataset_versions
            WHERE workspace_id = :workspace_id
              AND dataset_id = :dataset_id
              AND version_hash = :version_hash
            """)
    Optional<UUID> findVersionIdByHash(@Bind("dataset_id") UUID datasetId,
            @Bind("version_hash") String versionHash,
            @Bind("workspace_id") String workspaceId);

    @SqlQuery("""
            WITH version_sequences AS (
                SELECT
                    id,
                    ROW_NUMBER() OVER (PARTITION BY dataset_id ORDER BY id) AS seq_num
                FROM dataset_versions
                WHERE workspace_id = :workspace_id AND dataset_id = :dataset_id
            )
            SELECT
                dv.id,
                dv.dataset_id,
                dv.version_hash,
                CONCAT('v', vs.seq_num) AS version_name,
                dv.items_total,
                dv.items_added,
                dv.items_modified,
                dv.items_deleted,
                dv.change_description,
                dv.metadata,
                dv.evaluators,
                dv.execution_policy,
                dv.created_at,
                dv.created_by,
                dv.last_updated_at,
                dv.last_updated_by,
                COALESCE(t.tags, JSON_ARRAY()) AS tags,
                COALESCE(JSON_CONTAINS(t.tags, '"latest"'), false) AS is_latest
            FROM dataset_versions AS dv
            INNER JOIN version_sequences vs ON dv.id = vs.id
            LEFT JOIN (
                SELECT version_id, JSON_ARRAYAGG(tag) AS tags
                FROM dataset_version_tags
                WHERE version_id in (select id from version_sequences)
                GROUP BY version_id
            ) AS t ON t.version_id = dv.id
            WHERE dv.dataset_id = :dataset_id
                AND dv.workspace_id = :workspace_id
            ORDER BY dv.id DESC
            LIMIT :limit OFFSET :offset
            """)
    List<DatasetVersion> findByDatasetId(@Bind("dataset_id") UUID datasetId, @Bind("workspace_id") String workspaceId,
            @Bind("limit") int limit, @Bind("offset") int offset);

    @SqlQuery("SELECT COUNT(*) FROM dataset_versions WHERE dataset_id = :dataset_id AND workspace_id = :workspace_id")
    long countByDatasetId(@Bind("dataset_id") UUID datasetId, @Bind("workspace_id") String workspaceId);

    @SqlUpdate("""
            INSERT INTO dataset_version_tags (dataset_id, tag, version_id, created_by, last_updated_by, workspace_id)
            VALUES (:dataset_id, :tag, :version_id, :created_by, :created_by, :workspace_id)
            """)
    void insertTag(@Bind("dataset_id") UUID datasetId, @Bind("tag") String tag, @Bind("version_id") UUID versionId,
            @Bind("created_by") String createdBy, @Bind("workspace_id") String workspaceId);

    @SqlBatch("""
            INSERT INTO dataset_version_tags (dataset_id, tag, version_id, created_by, last_updated_by, workspace_id)
            VALUES (:dataset_id, :tag, :version_id, :created_by, :created_by, :workspace_id)
            """)
    void insertTags(@Bind("dataset_id") UUID datasetId, @Bind("tag") List<String> tags,
            @Bind("version_id") UUID versionId, @Bind("created_by") String createdBy,
            @Bind("workspace_id") String workspaceId);

    @SqlUpdate("DELETE FROM dataset_version_tags WHERE dataset_id = :dataset_id AND tag = :tag AND workspace_id = :workspace_id")
    int deleteTag(@Bind("dataset_id") UUID datasetId, @Bind("tag") String tag,
            @Bind("workspace_id") String workspaceId);

    @SqlUpdate("""
            DELETE FROM dataset_version_tags
            WHERE dataset_id = :dataset_id
                AND tag = :tag
                AND version_id = :version_id
                AND workspace_id = :workspace_id
            """)
    int deleteTagIfVersion(@Bind("dataset_id") UUID datasetId, @Bind("tag") String tag,
            @Bind("version_id") UUID versionId, @Bind("workspace_id") String workspaceId);

    @SqlQuery("""
            SELECT
                dv.id,
                dv.dataset_id,
                dv.version_hash,
                CONCAT('v', (
                    SELECT COUNT(*)
                    FROM dataset_versions s
                    WHERE s.workspace_id = :workspace_id
                        AND s.dataset_id = :dataset_id
                        AND s.id <= dv.id
                )) AS version_name,
                dv.items_total,
                dv.items_added,
                dv.items_modified,
                dv.items_deleted,
                dv.change_description,
                dv.metadata,
                dv.evaluators,
                dv.execution_policy,
                dv.created_at,
                dv.created_by,
                dv.last_updated_at,
                dv.last_updated_by,
                COALESCE((
                    SELECT JSON_ARRAYAGG(t.tag)
                    FROM dataset_version_tags t
                    WHERE t.version_id = dv.id
                ), JSON_ARRAY()) AS tags,
                EXISTS(
                    SELECT 1
                    FROM dataset_version_tags t2
                    WHERE t2.version_id = dv.id AND t2.tag = 'latest'
                ) AS is_latest
            FROM dataset_versions AS dv
            INNER JOIN dataset_version_tags dvt ON dv.id = dvt.version_id
            WHERE dvt.dataset_id = :dataset_id
                AND dvt.tag = :tag
                AND dv.workspace_id = :workspace_id
            """)
    Optional<DatasetVersion> findByTag(@Bind("dataset_id") UUID datasetId, @Bind("tag") String tag,
            @Bind("workspace_id") String workspaceId);

    @SqlQuery("""
            SELECT version_id
            FROM dataset_version_tags
            WHERE workspace_id = :workspace_id
              AND dataset_id = :dataset_id
              AND tag = :tag
            """)
    Optional<UUID> findVersionIdByTag(@Bind("dataset_id") UUID datasetId,
            @Bind("tag") String tag,
            @Bind("workspace_id") String workspaceId);

    @SqlQuery("""
            WITH version_sequences AS (
                SELECT
                    id,
                    ROW_NUMBER() OVER (PARTITION BY dataset_id ORDER BY id) AS seq_num
                FROM dataset_versions
                WHERE workspace_id = :workspace_id AND dataset_id = :dataset_id
            )
            SELECT
                dv.id,
                dv.dataset_id,
                dv.version_hash,
                CONCAT('v', vs.seq_num) AS version_name,
                dv.items_total,
                dv.items_added,
                dv.items_modified,
                dv.items_deleted,
                dv.change_description,
                dv.metadata,
                dv.evaluators,
                dv.execution_policy,
                dv.created_at,
                dv.created_by,
                dv.last_updated_at,
                dv.last_updated_by,
                COALESCE(t.tags, JSON_ARRAY()) AS tags,
                COALESCE(JSON_CONTAINS(t.tags, '"latest"'), false) AS is_latest
            FROM dataset_versions AS dv
            INNER JOIN version_sequences vs ON dv.id = vs.id
            LEFT JOIN (
                SELECT version_id, JSON_ARRAYAGG(tag) AS tags
                FROM dataset_version_tags
                WHERE version_id in (select id from version_sequences)
                GROUP BY version_id
            ) AS t ON t.version_id = dv.id
            WHERE dv.dataset_id = :dataset_id
                AND dv.workspace_id = :workspace_id
                AND CONCAT('v', vs.seq_num) = :version_name
            """)
    Optional<DatasetVersion> findByVersionName(@Bind("dataset_id") UUID datasetId,
            @Bind("version_name") String versionName,
            @Bind("workspace_id") String workspaceId);

    @SqlUpdate("""
            UPDATE dataset_versions
            SET change_description = :change_description,
                last_updated_by = :last_updated_by
            WHERE id = :id AND workspace_id = :workspace_id
            """)
    int updateChangeDescription(@Bind("id") UUID id, @Bind("change_description") String changeDescription,
            @Bind("last_updated_by") String lastUpdatedBy, @Bind("workspace_id") String workspaceId);

    /**
     * Resolves the 'latest'-tagged version per dataset. The latest version is always the max-id row,
     * so its version number equals the dataset's total version count: a correlated {@code COUNT(*)}
     * served by the covering index {@code idx_dataset_versions_workspace_id_dataset_id_id} replaces a
     * {@code ROW_NUMBER()} window over every version, which sorted the full version set into an
     * on-disk temp table at scale. The subquery runs once per result row (one latest row per dataset).
     */
    @SqlQuery("""
            SELECT
                dv.id,
                dv.dataset_id,
                dv.version_hash,
                CONCAT('v', (
                    SELECT COUNT(*)
                    FROM dataset_versions c
                    WHERE c.workspace_id = dv.workspace_id
                        AND c.dataset_id = dv.dataset_id
                )) AS version_name,
                dv.items_total,
                dv.items_added,
                dv.items_modified,
                dv.items_deleted,
                dv.change_description,
                dv.metadata,
                dv.evaluators,
                dv.execution_policy,
                dv.created_at,
                dv.created_by,
                dv.last_updated_at,
                dv.last_updated_by,
                COALESCE(t.tags, JSON_ARRAY()) AS tags,
                true AS is_latest
            FROM dataset_versions AS dv
            INNER JOIN dataset_version_tags dvt
                ON dvt.workspace_id = dv.workspace_id
                AND dvt.dataset_id = dv.dataset_id
                AND dvt.version_id = dv.id
                AND dvt.tag = 'latest'
            LEFT JOIN (
                SELECT version_id, JSON_ARRAYAGG(tag) AS tags
                FROM dataset_version_tags
                WHERE workspace_id = :workspace_id AND dataset_id IN (<dataset_ids>)
                GROUP BY version_id
            ) AS t ON t.version_id = dv.id
            WHERE dv.dataset_id IN (<dataset_ids>)
                AND dv.workspace_id = :workspace_id
            """)
    List<DatasetVersion> findLatestVersionsByDatasetIds(@BindList("dataset_ids") Collection<UUID> datasetIds,
            @Bind("workspace_id") String workspaceId);

    @SqlQuery("""
            WITH target_datasets AS (
                SELECT DISTINCT dataset_id
                FROM dataset_versions
                WHERE id IN (<version_ids>) AND workspace_id = :workspace_id
            ),
            version_sequences AS (
                SELECT
                    id,
                    ROW_NUMBER() OVER (PARTITION BY dataset_id ORDER BY id) AS seq_num
                FROM dataset_versions
                WHERE workspace_id = :workspace_id
                  AND dataset_id IN (SELECT dataset_id FROM target_datasets)
            )
            SELECT
                dv.id,
                dv.dataset_id,
                dv.version_hash,
                CONCAT('v', vs.seq_num) AS version_name,
                dv.items_total,
                dv.items_added,
                dv.items_modified,
                dv.items_deleted,
                dv.change_description,
                dv.metadata,
                dv.evaluators,
                dv.execution_policy,
                dv.created_at,
                dv.created_by,
                dv.last_updated_at,
                dv.last_updated_by,
                COALESCE(t.tags, JSON_ARRAY()) AS tags,
                COALESCE(JSON_CONTAINS(t.tags, '"latest"'), false) AS is_latest
            FROM dataset_versions AS dv
            INNER JOIN version_sequences vs ON dv.id = vs.id
            LEFT JOIN (
                SELECT version_id, JSON_ARRAYAGG(tag) AS tags
                FROM dataset_version_tags
                WHERE version_id IN (<version_ids>)
                GROUP BY version_id
            ) AS t ON t.version_id = dv.id
            WHERE dv.id IN (<version_ids>)
                AND dv.workspace_id = :workspace_id
            """)
    List<DatasetVersion> findByIds(@BindList("version_ids") Collection<UUID> versionIds,
            @Bind("workspace_id") String workspaceId);

    @SqlQuery("""
            SELECT dataset_id, dataset_name, dataset_type, created_at, created_by FROM (
                SELECT id AS dataset_id, name AS dataset_name, type AS dataset_type, created_at, created_by
                FROM datasets
                WHERE workspace_id = :workspace_id
                    AND project_id = :project_id
                    AND id >= :min_id

                UNION ALL

                SELECT d.id, d.name, d.type, dv.created_at, dv.created_by
                FROM dataset_versions dv
                INNER JOIN datasets d ON dv.dataset_id = d.id AND dv.workspace_id = d.workspace_id
                WHERE d.workspace_id = :workspace_id
                    AND d.project_id = :project_id
                    AND dv.id >= :min_id
                    AND dv.created_at > d.created_at
                    AND dv.items_total > 0
            ) combined
            ORDER BY created_at DESC
            LIMIT :limit
            """)
    @RegisterConstructorMapper(value = RecentActivity.RecentDatasetVersion.class)
    List<RecentActivity.RecentDatasetVersion> findRecentActivityByProjectId(
            @Bind("workspace_id") String workspaceId,
            @Bind("project_id") UUID projectId,
            @Bind("min_id") UUID minId,
            @Bind("limit") int limit);

    @SqlUpdate("DELETE FROM dataset_version_tags WHERE dataset_id IN (<dataset_ids>) AND workspace_id = :workspace_id")
    void deleteAllTagsByDatasetIds(@BindList("dataset_ids") Collection<UUID> datasetIds,
            @Bind("workspace_id") String workspaceId);

    @SqlUpdate("DELETE FROM dataset_versions WHERE dataset_id IN (<dataset_ids>) AND workspace_id = :workspace_id")
    void deleteAllVersionsByDatasetIds(@BindList("dataset_ids") Collection<UUID> datasetIds,
            @Bind("workspace_id") String workspaceId);

    @SqlUpdate("""
            UPDATE dataset_versions
            SET batch_group_id = :batch_group_id,
                last_updated_at = NOW(),
                last_updated_by = :last_updated_by
            WHERE id = :version_id
                AND workspace_id = :workspace_id
            """)
    void updateBatchGroupId(@Bind("version_id") UUID versionId,
            @Bind("batch_group_id") UUID batchGroupId,
            @Bind("workspace_id") String workspaceId,
            @Bind("last_updated_by") String lastUpdatedBy);

    /**
     * Applies signed deltas to the version counters in a single statement, so the arithmetic happens in the database
     * rather than as a read-modify-write in the application.
     * <p>
     * Concurrent <em>delta</em> writers compose without an external lock. The guarantee does not extend to the
     * absolute writers in this interface ({@link #updateItemsTotal}, {@link #batchUpdateItemsTotal}, both driven by
     * the items-total backfill), which still write a value derived from an earlier read and can overwrite an
     * increment that landed in between. Those are gated on the not-migrated sentinel so a row an increment has
     * already touched is skipped rather than clobbered.
     * <p>
     * Rows still holding {@link #ITEMS_TOTAL_NOT_MIGRATED} are excluded: adding a delta to the sentinel would
     * corrupt the counter and also hide the row from the backfill, which selects on that exact value. Such a row
     * reports zero affected rows, same as a missing or cross-workspace version. The predicate is the NULL-safe
     * {@code <=>} rather than {@code <>} so a NULL counter — which the COALESCE above exists to repair — is still
     * incremented instead of being skipped by three-valued logic.
     */
    @SqlUpdate("""
            UPDATE dataset_versions
            SET items_total = COALESCE(items_total, 0) + :items_total_delta,
                items_added = COALESCE(items_added, 0) + :items_added_delta,
                items_modified = COALESCE(items_modified, 0) + :items_modified_delta,
                items_deleted = COALESCE(items_deleted, 0) + :items_deleted_delta,
                last_updated_at = NOW(),
                last_updated_by = :last_updated_by
            WHERE id = :version_id
              AND workspace_id = :workspace_id
              AND NOT (items_total <=> :items_total_not_migrated)
            """)
    int incrementCounts(@Bind("version_id") UUID versionId,
            @Bind("items_total_delta") int itemsTotalDelta,
            @Bind("items_added_delta") int itemsAddedDelta,
            @Bind("items_modified_delta") int itemsModifiedDelta,
            @Bind("items_deleted_delta") int itemsDeletedDelta,
            @Bind("workspace_id") String workspaceId,
            @Bind("last_updated_by") String lastUpdatedBy,
            @Bind("items_total_not_migrated") int itemsTotalNotMigrated);

    /**
     * Applies the deltas, supplying the not-migrated sentinel so callers cannot forget the guard.
     *
     * @return affected rows: {@code 0} when the version is missing, belongs to another workspace, or still holds
     *         {@link #ITEMS_TOTAL_NOT_MIGRATED}
     */
    default int incrementCounts(UUID versionId, int itemsTotalDelta, int itemsAddedDelta, int itemsModifiedDelta,
            int itemsDeletedDelta, String workspaceId, String lastUpdatedBy) {
        return incrementCounts(versionId, itemsTotalDelta, itemsAddedDelta, itemsModifiedDelta, itemsDeletedDelta,
                workspaceId, lastUpdatedBy, ITEMS_TOTAL_NOT_MIGRATED);
    }

    @SqlUpdate("""
            INSERT INTO dataset_versions (
                id, dataset_id, version_hash, items_total, items_added, items_modified, items_deleted,
                change_description, metadata, created_by, last_updated_by, workspace_id,
                created_at, last_updated_at
            )
            SELECT
                :version_id,
                :dataset_id,
                'v1',
                :items_total_not_migrated,
                0,
                0,
                0,
                'Initial version',
                NULL,
                d.created_by,
                d.last_updated_by,
                d.workspace_id,
                d.created_at,
                d.last_updated_at
            FROM datasets d
            WHERE d.id = :dataset_id
              AND d.workspace_id = :workspace_id
              AND NOT EXISTS (
                  SELECT 1 FROM dataset_versions
                  WHERE dataset_id = :dataset_id
              )
            """)
    int ensureVersion1Exists(@Bind("dataset_id") UUID datasetId,
            @Bind("version_id") UUID versionId,
            @Bind("workspace_id") String workspaceId,
            @Bind("items_total_not_migrated") int itemsTotalNotMigrated);

    /**
     * Creates v1 for a lazily-migrated dataset, seeding {@code items_total} with
     * {@link #ITEMS_TOTAL_NOT_MIGRATED} rather than {@code 0}.
     * <p>
     * The row is created before its items are counted, so a literal {@code 0} would be indistinguishable from a
     * genuinely empty version: {@link #updateItemsTotal} would refuse to write the real count, and the batch
     * backfill would never select the row. Seeding the sentinel keeps both paths able to fix it.
     */
    default int ensureVersion1Exists(UUID datasetId, UUID versionId, String workspaceId) {
        return ensureVersion1Exists(datasetId, versionId, workspaceId, ITEMS_TOTAL_NOT_MIGRATED);
    }

    /**
     * Backfills {@code items_total} for a version that has not been migrated yet.
     * <p>
     * The value is absolute and derived from a ClickHouse count taken earlier, so it is gated on the row still
     * holding {@link #ITEMS_TOTAL_NOT_MIGRATED}: if an API write incremented the counter between that count and
     * this update, the row is skipped rather than overwritten with a stale total.
     *
     * @return affected rows: {@code 0} when the version no longer holds the sentinel
     */
    @SqlUpdate("""
            UPDATE dataset_versions
            SET items_total = :items_total,
                last_updated_at = NOW()
            WHERE workspace_id = :workspace_id
              AND id = :version_id
              AND items_total = :items_total_not_migrated
            """)
    int updateItemsTotal(@Bind("workspace_id") String workspaceId,
            @Bind("version_id") UUID versionId,
            @Bind("items_total") long itemsTotal,
            @Bind("items_total_not_migrated") int itemsTotalNotMigrated);

    /**
     * Backfills {@code items_total}, supplying the not-migrated sentinel so callers cannot forget the guard.
     */
    default int updateItemsTotal(String workspaceId, UUID versionId, long itemsTotal) {
        return updateItemsTotal(workspaceId, versionId, itemsTotal, ITEMS_TOTAL_NOT_MIGRATED);
    }

    /**
     * Batch update items_total for multiple dataset versions.
     * Uses JDBI's @SqlBatch to execute multiple updates efficiently in a single batch.
     * <p>
     * Gated on the not-migrated sentinel for the same reason as {@link #updateItemsTotal}: these totals come from
     * a ClickHouse count taken before the write, and must not clobber an increment that landed in between.
     *
     * @param workspaceIds list of workspace IDs (must match versionIds order and size)
     * @param versionIds list of version IDs to update
     * @param itemsTotals list of items_total values (must match versionIds order and size)
     */
    @SqlBatch("""
            UPDATE dataset_versions
            SET items_total = :items_total,
                last_updated_at = NOW()
            WHERE workspace_id = :workspace_id
              AND id = :version_id
              AND items_total = :items_total_not_migrated
            """)
    void batchUpdateItemsTotal(@Bind("workspace_id") List<String> workspaceIds,
            @Bind("version_id") List<UUID> versionIds,
            @Bind("items_total") List<Long> itemsTotals,
            @Bind("items_total_not_migrated") int itemsTotalNotMigrated);

    /**
     * Batch backfill, supplying the not-migrated sentinel so callers cannot forget the guard.
     */
    default void batchUpdateItemsTotal(List<String> workspaceIds, List<UUID> versionIds, List<Long> itemsTotals) {
        batchUpdateItemsTotal(workspaceIds, versionIds, itemsTotals, ITEMS_TOTAL_NOT_MIGRATED);
    }

    /**
     * Finds dataset versions that need items_total migration using cursor-based pagination.
     * These are versions where:
     * - dataset_id = id (version created by Liquibase migration)
     * - items_total = -1 (sentinel value indicating not yet migrated)
     * - id > lastSeenVersionId (for pagination)
     *
     * Returns workspace_id, dataset_id, and version_id to optimize ClickHouse queries
     * using the table's ordering key (workspace_id, dataset_id, dataset_version_id, id).
     *
     * @param lastSeenVersionId cursor for pagination (use empty string for first batch)
     * @param limit maximum number of versions to return
     * @return list of version info for migration
     */
    @SqlQuery("""
            SELECT workspace_id, dataset_id, id AS version_id
            FROM dataset_versions
            WHERE dataset_id = id
              AND items_total = -1
              AND id > :lastSeenVersionId
            ORDER BY id
            LIMIT :limit
            """)
    @RegisterConstructorMapper(DatasetVersionInfo.class)
    List<DatasetVersionInfo> findVersionsNeedingItemsTotalMigration(
            @Bind("lastSeenVersionId") String lastSeenVersionId,
            @Bind("limit") int limit);

    @SqlUpdate("""
            INSERT INTO dataset_version_tags (dataset_id, tag, version_id, created_by, last_updated_by, workspace_id, created_at, last_updated_at)
            SELECT
                :dataset_id,
                'latest',
                :version_id,
                d.created_by,
                d.last_updated_by,
                d.workspace_id,
                d.created_at,
                d.last_updated_at
            FROM datasets d
            WHERE d.id = :dataset_id
              AND d.workspace_id = :workspace_id
              AND NOT EXISTS (
                  SELECT 1 FROM dataset_version_tags
                  WHERE dataset_id = :dataset_id
                    AND tag = 'latest'
              )
            """)
    int ensureLatestTagExists(@Bind("dataset_id") UUID datasetId,
            @Bind("version_id") UUID versionId,
            @Bind("workspace_id") String workspaceId);
}

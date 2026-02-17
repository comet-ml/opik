package com.comet.opik.domain;

import com.comet.opik.api.DatasetVersion;
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
            """)
    Optional<DatasetVersion> findByBatchGroupId(@Bind("batch_group_id") UUID batchGroupId,
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
            INNER JOIN dataset_version_tags dvt ON dv.id = dvt.version_id
            LEFT JOIN (
                SELECT version_id, JSON_ARRAYAGG(tag) AS tags
                FROM dataset_version_tags
                WHERE version_id in (select id from version_sequences)
                GROUP BY version_id
            ) AS t ON t.version_id = dv.id
            WHERE dvt.dataset_id = :dataset_id
                AND dvt.tag = :tag
                AND dv.workspace_id = :workspace_id
            """)
    Optional<DatasetVersion> findByTag(@Bind("dataset_id") UUID datasetId, @Bind("tag") String tag,
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

    @SqlQuery("""
            WITH version_sequences AS (
                SELECT
                    id,
                    ROW_NUMBER() OVER (PARTITION BY dataset_id ORDER BY id) AS seq_num
                FROM dataset_versions
                WHERE workspace_id = :workspace_id AND dataset_id IN (<dataset_ids>)
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
                true AS is_latest
            FROM dataset_versions AS dv
            INNER JOIN version_sequences vs ON dv.id = vs.id
            INNER JOIN (
                SELECT version_id, JSON_ARRAYAGG(tag) AS tags
                FROM dataset_version_tags
                WHERE tag = 'latest'
                AND version_id in (select id from version_sequences)
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

    @SqlUpdate("""
            UPDATE dataset_versions
            SET items_total = :items_total,
                items_added = :items_added,
                items_modified = :items_modified,
                items_deleted = :items_deleted,
                last_updated_at = NOW(),
                last_updated_by = :last_updated_by
            WHERE id = :version_id
              AND workspace_id = :workspace_id
            """)
    void updateCounts(@Bind("version_id") UUID versionId,
            @Bind("items_total") int itemsTotal,
            @Bind("items_added") int itemsAdded,
            @Bind("items_modified") int itemsModified,
            @Bind("items_deleted") int itemsDeleted,
            @Bind("workspace_id") String workspaceId,
            @Bind("last_updated_by") String lastUpdatedBy);

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
                0,
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
            @Bind("workspace_id") String workspaceId);

    @SqlUpdate("""
            UPDATE dataset_versions
            SET items_total = :items_total,
                last_updated_at = NOW()
            WHERE workspace_id = :workspace_id
              AND id = :version_id
            """)
    void updateItemsTotal(@Bind("workspace_id") String workspaceId,
            @Bind("version_id") UUID versionId,
            @Bind("items_total") long itemsTotal);

    /**
     * Batch update items_total for multiple dataset versions.
     * Uses JDBI's @SqlBatch to execute multiple updates efficiently in a single batch.
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
            """)
    void batchUpdateItemsTotal(@Bind("workspace_id") List<String> workspaceIds,
            @Bind("version_id") List<UUID> versionIds,
            @Bind("items_total") List<Long> itemsTotals);

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

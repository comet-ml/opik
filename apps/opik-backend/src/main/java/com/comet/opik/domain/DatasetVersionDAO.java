package com.comet.opik.domain;

import com.comet.opik.api.DatasetVersion;
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
@RegisterColumnMapper(MapFlatArgumentFactory.class)
@RegisterColumnMapper(SequencedSetColumnMapper.class)
@RegisterConstructorMapper(DatasetVersion.class)
public interface DatasetVersionDAO {

    @SqlUpdate("""
            INSERT INTO dataset_versions (
                id, dataset_id, version_hash, version_sequence, items_total, items_added, items_modified, items_deleted,
                change_description, metadata, created_by, last_updated_by, workspace_id
            ) VALUES (
                :version.id, :version.datasetId, :version.versionHash,
                (SELECT COALESCE(MAX(dv.version_sequence), 0) + 1
                 FROM (SELECT version_sequence FROM dataset_versions
                       WHERE dataset_id = :version.datasetId AND workspace_id = :workspace_id) AS dv),
                :version.itemsTotal, :version.itemsAdded, :version.itemsModified, :version.itemsDeleted,
                :version.changeDescription, :version.metadata, :version.createdBy, :version.lastUpdatedBy, :workspace_id
            )
            """)
    void insert(@BindMethods("version") DatasetVersion version, @Bind("workspace_id") String workspaceId);

    @SqlQuery("""
            SELECT
                dv.id,
                dv.dataset_id,
                dv.version_hash,
                CONCAT('v', dv.version_sequence) AS version_sequence,
                dv.items_total,
                dv.items_added,
                dv.items_modified,
                dv.items_deleted,
                dv.change_description,
                dv.metadata,
                dv.created_at,
                dv.created_by,
                dv.last_updated_at,
                dv.last_updated_by,
                COALESCE(t.tags, JSON_ARRAY()) AS tags,
                COALESCE(JSON_CONTAINS(t.tags, '"latest"'), false) AS is_latest
            FROM dataset_versions AS dv
            LEFT JOIN (
                SELECT version_id, JSON_ARRAYAGG(tag) AS tags
                FROM dataset_version_tags
                GROUP BY version_id
            ) AS t ON t.version_id = dv.id
            WHERE dv.id = :id AND dv.workspace_id = :workspace_id
            """)
    Optional<DatasetVersion> findById(@Bind("id") UUID id, @Bind("workspace_id") String workspaceId);

    @SqlQuery("""
            SELECT
                dv.id,
                dv.dataset_id,
                dv.version_hash,
                CONCAT('v', dv.version_sequence) AS version_sequence,
                dv.items_total,
                dv.items_added,
                dv.items_modified,
                dv.items_deleted,
                dv.change_description,
                dv.metadata,
                dv.created_at,
                dv.created_by,
                dv.last_updated_at,
                dv.last_updated_by,
                COALESCE(t.tags, JSON_ARRAY()) AS tags,
                COALESCE(JSON_CONTAINS(t.tags, '"latest"'), false) AS is_latest
            FROM dataset_versions AS dv
            LEFT JOIN (
                SELECT version_id, JSON_ARRAYAGG(tag) AS tags
                FROM dataset_version_tags
                GROUP BY version_id
            ) AS t ON t.version_id = dv.id
            WHERE dv.dataset_id = :dataset_id
                AND dv.version_hash = :version_hash
                AND dv.workspace_id = :workspace_id
            """)
    Optional<DatasetVersion> findByHash(@Bind("dataset_id") UUID datasetId, @Bind("version_hash") String versionHash,
            @Bind("workspace_id") String workspaceId);

    @SqlQuery("""
            SELECT
                dv.id,
                dv.dataset_id,
                dv.version_hash,
                CONCAT('v', dv.version_sequence) AS version_sequence,
                dv.items_total,
                dv.items_added,
                dv.items_modified,
                dv.items_deleted,
                dv.change_description,
                dv.metadata,
                dv.created_at,
                dv.created_by,
                dv.last_updated_at,
                dv.last_updated_by,
                COALESCE(t.tags, JSON_ARRAY()) AS tags,
                COALESCE(JSON_CONTAINS(t.tags, '"latest"'), false) AS is_latest
            FROM dataset_versions AS dv
            LEFT JOIN (
                SELECT version_id, JSON_ARRAYAGG(tag) AS tags
                FROM dataset_version_tags
                GROUP BY version_id
            ) AS t ON t.version_id = dv.id
            WHERE dv.dataset_id = :dataset_id
                AND dv.workspace_id = :workspace_id
            ORDER BY dv.version_sequence DESC
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
            SELECT
                dv.id,
                dv.dataset_id,
                dv.version_hash,
                CONCAT('v', dv.version_sequence) AS version_sequence,
                dv.items_total,
                dv.items_added,
                dv.items_modified,
                dv.items_deleted,
                dv.change_description,
                dv.metadata,
                dv.created_at,
                dv.created_by,
                dv.last_updated_at,
                dv.last_updated_by,
                COALESCE(t.tags, JSON_ARRAY()) AS tags,
                COALESCE(JSON_CONTAINS(t.tags, '"latest"'), false) AS is_latest
            FROM dataset_versions AS dv
            INNER JOIN dataset_version_tags dvt ON dv.id = dvt.version_id
            LEFT JOIN (
                SELECT version_id, JSON_ARRAYAGG(tag) AS tags
                FROM dataset_version_tags
                GROUP BY version_id
            ) AS t ON t.version_id = dv.id
            WHERE dvt.dataset_id = :dataset_id
                AND dvt.tag = :tag
                AND dv.workspace_id = :workspace_id
            """)
    Optional<DatasetVersion> findByTag(@Bind("dataset_id") UUID datasetId, @Bind("tag") String tag,
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
            SELECT
                dv.id,
                dv.dataset_id,
                dv.version_hash,
                CONCAT('v', dv.version_sequence) AS version_sequence,
                dv.items_total,
                dv.items_added,
                dv.items_modified,
                dv.items_deleted,
                dv.change_description,
                dv.metadata,
                dv.created_at,
                dv.created_by,
                dv.last_updated_at,
                dv.last_updated_by,
                COALESCE(t.tags, JSON_ARRAY()) AS tags,
                true AS is_latest
            FROM dataset_versions AS dv
            INNER JOIN (
                SELECT version_id, JSON_ARRAYAGG(tag) AS tags
                FROM dataset_version_tags
                GROUP BY version_id
            ) AS t ON t.version_id = dv.id AND JSON_CONTAINS(t.tags, '"latest"')
            WHERE dv.dataset_id IN (<dataset_ids>)
                AND dv.workspace_id = :workspace_id
            """)
    List<DatasetVersion> findLatestVersionsByDatasetIds(@BindList("dataset_ids") Collection<UUID> datasetIds,
            @Bind("workspace_id") String workspaceId);
}

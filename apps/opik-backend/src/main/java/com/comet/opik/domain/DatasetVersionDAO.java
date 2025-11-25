package com.comet.opik.domain;

import com.comet.opik.api.DatasetVersion;
import com.comet.opik.infrastructure.db.MapFlatArgumentFactory;
import com.comet.opik.infrastructure.db.SequencedSetColumnMapper;
import com.comet.opik.infrastructure.db.UUIDArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterColumnMapper;
import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindMethods;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

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
                id, dataset_id, version_hash, items_total, items_added, items_modified, items_deleted,
                change_description, metadata, created_by, last_updated_by, workspace_id
            ) VALUES (
                :version.id, :version.datasetId, :version.versionHash, :version.itemsTotal,
                :version.itemsAdded, :version.itemsModified, :version.itemsDeleted,
                :version.changeDescription, :version.metadata, :version.createdBy, :version.lastUpdatedBy, :workspace_id
            )
            """)
    void insert(@BindMethods("version") DatasetVersion version, @Bind("workspace_id") String workspaceId);

    @SqlQuery("""
            SELECT
                dv.*,
                COALESCE(t.tags, JSON_ARRAY()) AS tags
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
                dv.*,
                COALESCE(t.tags, JSON_ARRAY()) AS tags
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
                dv.*,
                COALESCE(t.tags, JSON_ARRAY()) AS tags
            FROM dataset_versions AS dv
            LEFT JOIN (
                SELECT version_id, JSON_ARRAYAGG(tag) AS tags
                FROM dataset_version_tags
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

    @SqlUpdate("DELETE FROM dataset_version_tags WHERE dataset_id = :dataset_id AND tag = :tag AND workspace_id = :workspace_id")
    int deleteTag(@Bind("dataset_id") UUID datasetId, @Bind("tag") String tag,
            @Bind("workspace_id") String workspaceId);

    @SqlQuery("""
            SELECT
                dv.*,
                COALESCE(t.tags, JSON_ARRAY()) AS tags
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
}

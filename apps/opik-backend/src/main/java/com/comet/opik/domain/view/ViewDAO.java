package com.comet.opik.domain.view;

import com.comet.opik.api.view.View;
import com.comet.opik.api.view.ViewUpdate;
import com.comet.opik.api.view.ViewVersion;
import com.comet.opik.api.view.ViewVersionPage;
import com.comet.opik.infrastructure.db.SetFlatArgumentFactory;
import com.comet.opik.infrastructure.db.UUIDArgumentFactory;
import com.comet.opik.infrastructure.db.ViewAreaMapper;
import com.comet.opik.infrastructure.db.ViewAuthorMapper;
import com.comet.opik.infrastructure.db.ViewInputsColumnMapper;
import com.comet.opik.infrastructure.db.ViewTypeMapper;
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
@RegisterArgumentFactory(ViewAreaMapper.class)
@RegisterArgumentFactory(ViewAuthorMapper.class)
@RegisterArgumentFactory(ViewTypeMapper.class)
@RegisterArgumentFactory(ViewInputsColumnMapper.class)
@RegisterArgumentFactory(SetFlatArgumentFactory.class)
@RegisterColumnMapper(ViewAreaMapper.class)
@RegisterColumnMapper(ViewAuthorMapper.class)
@RegisterColumnMapper(ViewTypeMapper.class)
@RegisterColumnMapper(ViewInputsColumnMapper.class)
@RegisterColumnMapper(SetFlatArgumentFactory.class)
@RegisterConstructorMapper(View.class)
@RegisterConstructorMapper(ViewVersion.class)
@RegisterConstructorMapper(ViewVersionPage.Item.class)
interface ViewDAO {

    @SqlUpdate("""
            INSERT INTO views(id, workspace_id, area, name, description, current_version, last_version, created_by,
                              last_updated_by)
            VALUES (:view.id, :workspaceId, :view.area, :view.name, NULLIF(TRIM(:view.description), ''), :view.currentVersion,
                    :view.currentVersion, :view.createdBy, :view.lastUpdatedBy)
            """)
    void save(@BindMethods("view") View view, @Bind("workspaceId") String workspaceId);

    @SqlQuery("""
            SELECT id, area, name, description, current_version, created_at, created_by, last_updated_at,
                   last_updated_by
            FROM views
            WHERE id = :id AND workspace_id = :workspaceId AND deleted_at IS NULL
            """)
    Optional<View> findById(@Bind("id") UUID id, @Bind("workspaceId") String workspaceId);

    @SqlQuery("""
            SELECT current_version FROM views
            WHERE id = :id AND workspace_id = :workspaceId AND deleted_at IS NULL
            FOR UPDATE
            """)
    Optional<Integer> lockById(@Bind("id") UUID id, @Bind("workspaceId") String workspaceId);

    @SqlUpdate("""
            UPDATE views SET
                name = COALESCE(:update.name, name),
                description = IF(:update.description IS NULL, description, NULLIF(TRIM(:update.description), '')),
                last_updated_by = :lastUpdatedBy
            WHERE id = :id AND workspace_id = :workspaceId AND deleted_at IS NULL
            """)
    int update(@Bind("id") UUID id, @Bind("workspaceId") String workspaceId,
            @BindMethods("update") ViewUpdate update, @Bind("lastUpdatedBy") String lastUpdatedBy);

    @SqlUpdate("""
            UPDATE views SET current_version = :version, last_updated_by = :lastUpdatedBy
            WHERE id = :id AND workspace_id = :workspaceId AND deleted_at IS NULL
            """)
    int setCurrentVersion(@Bind("id") UUID id, @Bind("workspaceId") String workspaceId,
            @Bind("version") int version, @Bind("lastUpdatedBy") String lastUpdatedBy);

    @SqlUpdate("""
            UPDATE views SET last_updated_at = CURRENT_TIMESTAMP(6), last_updated_by = :lastUpdatedBy
            WHERE id = :id AND workspace_id = :workspaceId AND deleted_at IS NULL
            """)
    int updateLastUpdatedAt(@Bind("id") UUID id, @Bind("workspaceId") String workspaceId,
            @Bind("lastUpdatedBy") String lastUpdatedBy);

    @SqlUpdate("""
            UPDATE views SET deleted_at = CURRENT_TIMESTAMP(6), last_updated_by = :lastUpdatedBy
            WHERE id = :id AND workspace_id = :workspaceId AND deleted_at IS NULL
            """)
    int softDelete(@Bind("id") UUID id, @Bind("workspaceId") String workspaceId,
            @Bind("lastUpdatedBy") String lastUpdatedBy);

    @SqlUpdate("""
            INSERT INTO view_versions(view_id, version, author, summary, type, engine_version, inputs, libraries, html,
                                      created_by)
            SELECT id, :version.version, :version.author, NULLIF(TRIM(:version.summary), ''), :version.type, :version.engineVersion,
                   :version.inputs, :version.libraries, :version.html, :version.createdBy
            FROM views
            WHERE id = :version.viewId AND workspace_id = :workspaceId
            """)
    int saveVersion(@BindMethods("version") ViewVersion version, @Bind("workspaceId") String workspaceId);

    @SqlUpdate("""
            UPDATE views SET last_version = last_version + 1
            WHERE id = :id AND workspace_id = :workspaceId AND deleted_at IS NULL
            """)
    int bumpLastVersion(@Bind("id") UUID id, @Bind("workspaceId") String workspaceId);

    @SqlQuery("SELECT last_version FROM views WHERE id = :id AND workspace_id = :workspaceId")
    int findLastVersion(@Bind("id") UUID id, @Bind("workspaceId") String workspaceId);

    @SqlUpdate("""
            DELETE vv FROM view_versions vv
            JOIN views v ON v.id = vv.view_id
            WHERE vv.view_id = :viewId AND vv.version = :version AND v.workspace_id = :workspaceId
            """)
    int deleteVersion(@Bind("viewId") UUID viewId, @Bind("version") int version,
            @Bind("workspaceId") String workspaceId);

    @SqlUpdate("""
            DELETE vv FROM view_versions vv
            JOIN views v ON v.id = vv.view_id
            WHERE vv.view_id = :viewId AND v.workspace_id = :workspaceId
            """)
    int deleteVersions(@Bind("viewId") UUID viewId, @Bind("workspaceId") String workspaceId);

    @SqlQuery("""
            SELECT vv.view_id, vv.version, vv.author, vv.summary, vv.type, vv.engine_version, vv.inputs, vv.libraries,
                   vv.html, vv.created_at, vv.created_by
            FROM view_versions vv
            JOIN views v ON v.id = vv.view_id
            WHERE vv.view_id = :viewId AND vv.version = :version AND v.workspace_id = :workspaceId
            """)
    Optional<ViewVersion> findVersion(@Bind("viewId") UUID viewId, @Bind("version") int version,
            @Bind("workspaceId") String workspaceId);

    @SqlQuery("""
            SELECT vv.version, vv.author, vv.summary, vv.created_at, vv.created_by
            FROM view_versions vv
            JOIN views v ON v.id = vv.view_id
            WHERE vv.view_id = :viewId AND v.workspace_id = :workspaceId
            ORDER BY vv.version DESC
            LIMIT :limit OFFSET :offset
            """)
    List<ViewVersionPage.Item> findVersions(@Bind("viewId") UUID viewId, @Bind("workspaceId") String workspaceId,
            @Bind("limit") int limit, @Bind("offset") long offset);

    @SqlQuery("""
            SELECT COUNT(*) FROM view_versions vv
            JOIN views v ON v.id = vv.view_id
            WHERE vv.view_id = :viewId AND v.workspace_id = :workspaceId
            """)
    long countVersions(@Bind("viewId") UUID viewId, @Bind("workspaceId") String workspaceId);
}

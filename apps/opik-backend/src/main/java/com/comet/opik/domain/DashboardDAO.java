package com.comet.opik.domain;

import com.comet.opik.api.Dashboard;
import com.comet.opik.api.DashboardUpdate;
import com.comet.opik.infrastructure.db.JsonNodeArgumentFactory;
import com.comet.opik.infrastructure.db.JsonNodeColumnMapper;
import com.comet.opik.infrastructure.db.UUIDArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterColumnMapper;
import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.AllowUnusedBindings;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindList;
import org.jdbi.v3.sqlobject.customizer.BindMethods;
import org.jdbi.v3.sqlobject.customizer.Define;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;
import org.jdbi.v3.stringtemplate4.UseStringTemplateEngine;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@RegisterArgumentFactory(UUIDArgumentFactory.class)
@RegisterArgumentFactory(JsonNodeArgumentFactory.class)
@RegisterColumnMapper(JsonNodeColumnMapper.class)
@RegisterConstructorMapper(Dashboard.class)
public interface DashboardDAO {

    @SqlUpdate("INSERT INTO dashboards(id, workspace_id, name, slug, description, config, created_by, last_updated_by) "
            +
            "VALUES (:dashboard.id, :workspaceId, :dashboard.name, :dashboard.slug, :dashboard.description, :dashboard.config, :dashboard.createdBy, :dashboard.lastUpdatedBy)")
    void save(@BindMethods("dashboard") Dashboard dashboard, @Bind("workspaceId") String workspaceId);

    @SqlUpdate("""
            UPDATE dashboards SET
                name = COALESCE(:dashboard.name, name),
                slug = COALESCE(:slug, slug),
                description = COALESCE(:dashboard.description, description),
                config = COALESCE(:dashboard.config, config),
                last_updated_by = :lastUpdatedBy
            WHERE id = :id AND workspace_id = :workspaceId
            """)
    @AllowUnusedBindings
    int update(@Bind("workspaceId") String workspaceId,
            @Bind("id") UUID id,
            @BindMethods("dashboard") DashboardUpdate dashboard,
            @Bind("slug") String slug,
            @Bind("lastUpdatedBy") String lastUpdatedBy);

    @SqlQuery("SELECT * FROM dashboards WHERE id = :id AND workspace_id = :workspaceId")
    Optional<Dashboard> findById(@Bind("id") UUID id, @Bind("workspaceId") String workspaceId);

    @SqlQuery("SELECT * FROM dashboards WHERE workspace_id = :workspaceId AND name = :name")
    Optional<Dashboard> findByName(@Bind("workspaceId") String workspaceId, @Bind("name") String name);

    @SqlQuery("SELECT * FROM dashboards WHERE workspace_id = :workspaceId AND slug = :slug")
    Optional<Dashboard> findBySlug(@Bind("workspaceId") String workspaceId, @Bind("slug") String slug);

    @SqlUpdate("DELETE FROM dashboards WHERE id = :id AND workspace_id = :workspaceId")
    int delete(@Bind("id") UUID id, @Bind("workspaceId") String workspaceId);

    @SqlQuery("SELECT COUNT(id) FROM dashboards " +
            "WHERE workspace_id = :workspaceId " +
            "<if(search)> AND name like concat('%', :search, '%') <endif>")
    @UseStringTemplateEngine
    @AllowUnusedBindings
    long findCount(@Bind("workspaceId") String workspaceId,
            @Define("search") @Bind("search") String search);

    @SqlQuery("SELECT * FROM dashboards " +
            "WHERE workspace_id = :workspaceId " +
            "<if(search)> AND name like concat('%', :search, '%') <endif> " +
            "ORDER BY <if(sort_fields)> <sort_fields>, <endif> id DESC " +
            "LIMIT :limit OFFSET :offset")
    @UseStringTemplateEngine
    @AllowUnusedBindings
    List<Dashboard> find(@Bind("workspaceId") String workspaceId,
            @Define("search") @Bind("search") String search,
            @Define("sort_fields") String sortingFields,
            @Bind("limit") int limit,
            @Bind("offset") int offset);

    @SqlQuery("SELECT COUNT(*) FROM dashboards WHERE workspace_id = :workspaceId AND slug LIKE concat(:slugPrefix, '%')")
    long countBySlugPrefix(@Bind("workspaceId") String workspaceId, @Bind("slugPrefix") String slugPrefix);

    @SqlUpdate("DELETE FROM dashboards WHERE id IN (<ids>) AND workspace_id = :workspaceId")
    void delete(@BindList("ids") Set<UUID> ids, @Bind("workspaceId") String workspaceId);
}

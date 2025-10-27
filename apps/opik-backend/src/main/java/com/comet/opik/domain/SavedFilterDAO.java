package com.comet.opik.domain;

import com.comet.opik.api.FilterType;
import com.comet.opik.api.SavedFilter;
import com.comet.opik.infrastructure.db.UUIDArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterArgumentFactory;
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

@RegisterConstructorMapper(SavedFilter.class)
@RegisterArgumentFactory(UUIDArgumentFactory.class)
interface SavedFilterDAO {

    @SqlUpdate("INSERT INTO saved_filters (id, workspace_id, project_id, name, description, filters, filter_type, created_by, last_updated_by) "
            +
            "VALUES (:bean.id, :workspaceId, :bean.projectId, :bean.name, :bean.description, :filters, :bean.filterType, :bean.createdBy, :bean.lastUpdatedBy)")
    void save(@Bind("workspaceId") String workspaceId,
            @BindMethods("bean") SavedFilter savedFilter,
            @Bind("filters") String filters);

    @SqlUpdate("UPDATE saved_filters SET " +
            "name = COALESCE(:name, name), " +
            "description = COALESCE(:description, description), " +
            "filters = COALESCE(:filters, filters), " +
            "last_updated_by = :lastUpdatedBy " +
            "WHERE id = :id AND workspace_id = :workspaceId")
    void update(@Bind("id") UUID id,
            @Bind("workspaceId") String workspaceId,
            @Bind("name") String name,
            @Bind("description") String description,
            @Bind("filters") String filters,
            @Bind("lastUpdatedBy") String lastUpdatedBy);

    @SqlUpdate("DELETE FROM saved_filters WHERE id = :id AND workspace_id = :workspaceId")
    void delete(@Bind("id") UUID id, @Bind("workspaceId") String workspaceId);

    @SqlUpdate("DELETE FROM saved_filters WHERE id IN (<ids>) AND workspace_id = :workspaceId")
    void delete(@BindList("ids") Set<UUID> ids, @Bind("workspaceId") String workspaceId);

    @SqlQuery("SELECT * FROM saved_filters WHERE id = :id AND workspace_id = :workspaceId")
    SavedFilter findById(@Bind("id") UUID id, @Bind("workspaceId") String workspaceId);

    @SqlQuery("SELECT COUNT(*) FROM saved_filters " +
            " WHERE workspace_id = :workspaceId " +
            " AND project_id = :projectId " +
            " <if(name)> AND name like concat('%', :name, '%') <endif> " +
            " <if(filterType)> AND filter_type = :filterType <endif> ")
    @UseStringTemplateEngine
    @AllowUnusedBindings
    long findCount(@Bind("workspaceId") String workspaceId,
            @Bind("projectId") UUID projectId,
            @Define("name") @Bind("name") String name,
            @Define("filterType") @Bind("filterType") FilterType filterType);

    @SqlQuery("SELECT * FROM saved_filters " +
            " WHERE workspace_id = :workspaceId " +
            " AND project_id = :projectId " +
            " <if(name)> AND name like concat('%', :name, '%') <endif> " +
            " <if(filterType)> AND filter_type = :filterType <endif> " +
            " ORDER BY <if(sort_fields)> <sort_fields>, <endif> created_at DESC " +
            " LIMIT :limit OFFSET :offset ")
    @UseStringTemplateEngine
    @AllowUnusedBindings
    List<SavedFilter> find(@Bind("limit") int limit,
            @Bind("offset") int offset,
            @Bind("workspaceId") String workspaceId,
            @Bind("projectId") UUID projectId,
            @Define("name") @Bind("name") String name,
            @Define("filterType") @Bind("filterType") FilterType filterType,
            @Define("sort_fields") @Bind("sort_fields") String sortingFields);

    default Optional<SavedFilter> fetch(UUID id, String workspaceId) {
        return Optional.ofNullable(findById(id, workspaceId));
    }
}

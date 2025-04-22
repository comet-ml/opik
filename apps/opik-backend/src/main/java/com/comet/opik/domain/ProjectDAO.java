package com.comet.opik.domain;

import com.comet.opik.api.Project;
import com.comet.opik.api.ProjectIdLastUpdated;
import com.comet.opik.api.Visibility;
import com.comet.opik.infrastructure.db.UUIDArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.AllowUnusedBindings;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindList;
import org.jdbi.v3.sqlobject.customizer.BindMethods;
import org.jdbi.v3.sqlobject.customizer.Define;
import org.jdbi.v3.sqlobject.statement.SqlBatch;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;
import org.jdbi.v3.stringtemplate4.UseStringTemplateEngine;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@RegisterConstructorMapper(Project.class)
@RegisterConstructorMapper(ProjectIdLastUpdated.class)
@RegisterArgumentFactory(UUIDArgumentFactory.class)
interface ProjectDAO {

    @SqlUpdate("INSERT INTO projects (id, name, description, workspace_id, visibility, created_by, last_updated_by) VALUES (:bean.id, :bean.name, :bean.description, :workspaceId, COALESCE(:bean.visibility, 'private'), :bean.createdBy, :bean.lastUpdatedBy)")
    void save(@Bind("workspaceId") String workspaceId, @BindMethods("bean") Project project);

    @SqlUpdate("UPDATE projects SET " +
            "name = COALESCE(:name, name), " +
            "description = COALESCE(:description, description), " +
            "visibility = COALESCE(:visibility, visibility), " +
            "last_updated_by = :lastUpdatedBy " +
            "WHERE id = :id AND workspace_id = :workspaceId")
    void update(@Bind("id") UUID id,
            @Bind("workspaceId") String workspaceId,
            @Bind("name") String name,
            @Bind("description") String description,
            @Bind("visibility") Visibility visibility,
            @Bind("lastUpdatedBy") String lastUpdatedBy);

    @SqlUpdate("DELETE FROM projects WHERE id = :id AND workspace_id = :workspaceId")
    void delete(@Bind("id") UUID id, @Bind("workspaceId") String workspaceId);

    @SqlUpdate("DELETE FROM projects WHERE id IN (<ids>) AND workspace_id = :workspaceId")
    void delete(@BindList("ids") Set<UUID> ids, @Bind("workspaceId") String workspaceId);

    @SqlQuery("SELECT * FROM projects WHERE id = :id AND workspace_id = :workspaceId")
    Project findById(@Bind("id") UUID id, @Bind("workspaceId") String workspaceId);

    @SqlQuery("SELECT * FROM projects WHERE id IN (<ids>) AND workspace_id = :workspaceId")
    List<Project> findByIds(@BindList("ids") Set<UUID> ids, @Bind("workspaceId") String workspaceId);

    @SqlQuery("SELECT COUNT(*) FROM projects " +
            " WHERE workspace_id = :workspaceId " +
            " <if(name)> AND name like concat('%', :name, '%') <endif> " +
            " <if(visibility)> AND visibility = :visibility <endif> ")
    @UseStringTemplateEngine
    @AllowUnusedBindings
    long findCount(@Bind("workspaceId") String workspaceId,
            @Define("name") @Bind("name") String name,
            @Define("visibility") @Bind("visibility") Visibility visibility);

    @SqlQuery("SELECT * FROM projects " +
            " WHERE workspace_id = :workspaceId " +
            " <if(name)> AND name like concat('%', :name, '%') <endif> " +
            " <if(visibility)> AND visibility = :visibility <endif> " +
            " ORDER BY <if(sort_fields)> <sort_fields>, <endif> id DESC " +
            " LIMIT :limit OFFSET :offset ")
    @UseStringTemplateEngine
    @AllowUnusedBindings
    List<Project> find(@Bind("limit") int limit,
            @Bind("offset") int offset,
            @Bind("workspaceId") String workspaceId,
            @Define("name") @Bind("name") String name,
            @Define("visibility") @Bind("visibility") Visibility visibility,
            @Define("sort_fields") @Bind("sort_fields") String sortingFields);

    @SqlQuery("SELECT id, last_updated_at FROM projects" +
            " WHERE workspace_id = :workspaceId" +
            " <if(name)> AND name like concat('%', :name, '%') <endif>" +
            " <if(visibility)> AND visibility = :visibility <endif> ")
    @UseStringTemplateEngine
    @AllowUnusedBindings
    List<ProjectIdLastUpdated> getAllProjectIdsLastUpdated(@Bind("workspaceId") String workspaceId,
            @Define("name") @Bind("name") String name,
            @Define("visibility") @Bind("visibility") Visibility visibility);

    default Optional<Project> fetch(UUID id, String workspaceId) {
        return Optional.ofNullable(findById(id, workspaceId));
    }

    @SqlQuery("SELECT * FROM projects WHERE workspace_id = :workspaceId AND name IN (<names>)")
    List<Project> findByNames(@Bind("workspaceId") String workspaceId, @BindList("names") Collection<String> names);

    @SqlBatch("UPDATE projects SET last_updated_trace_at = :lastUpdatedAt " +
            "WHERE workspace_id = :workspace_id" +
            " AND id = :id" +
            " AND (last_updated_trace_at IS NULL OR last_updated_trace_at < :lastUpdatedAt)")
    int[] recordLastUpdatedTrace(@Bind("workspace_id") String workspaceId,
            @BindMethods Collection<ProjectIdLastUpdated> lastUpdatedTraces);
}

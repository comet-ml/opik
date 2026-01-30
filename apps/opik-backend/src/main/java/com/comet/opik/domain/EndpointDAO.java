package com.comet.opik.domain;

import com.comet.opik.api.Endpoint;
import com.comet.opik.api.EndpointUpdate;
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

@RegisterArgumentFactory(UUIDArgumentFactory.class)
@RegisterConstructorMapper(Endpoint.class)
interface EndpointDAO {

    @SqlUpdate("INSERT INTO endpoints (id, project_id, workspace_id, name, url, secret, schema_json, created_by, last_updated_by) "
            + "VALUES (:bean.id, :bean.projectId, :workspaceId, :bean.name, :bean.url, :bean.secret, :bean.schemaJson, :bean.createdBy, :bean.lastUpdatedBy)")
    void save(@Bind("workspaceId") String workspaceId, @BindMethods("bean") Endpoint endpoint);

    @SqlUpdate("UPDATE endpoints SET "
            + "name = COALESCE(:bean.name, name), "
            + "url = COALESCE(:bean.url, url), "
            + "secret = CASE WHEN :bean.secret IS NULL THEN secret ELSE :bean.secret END, "
            + "schema_json = CASE WHEN :bean.schemaJson IS NULL THEN schema_json ELSE :bean.schemaJson END, "
            + "last_updated_by = :lastUpdatedBy "
            + "WHERE id = :id AND workspace_id = :workspaceId")
    void update(@Bind("id") UUID id, @Bind("workspaceId") String workspaceId,
            @Bind("lastUpdatedBy") String lastUpdatedBy,
            @BindMethods("bean") EndpointUpdate endpoint);

    @SqlUpdate("DELETE FROM endpoints WHERE id = :id AND workspace_id = :workspaceId")
    void delete(@Bind("id") UUID id, @Bind("workspaceId") String workspaceId);

    @SqlUpdate("DELETE FROM endpoints WHERE id IN (<ids>) AND workspace_id = :workspaceId")
    void delete(@BindList("ids") Set<UUID> ids, @Bind("workspaceId") String workspaceId);

    @SqlQuery("SELECT * FROM endpoints WHERE id = :id AND workspace_id = :workspaceId")
    Endpoint findById(@Bind("id") UUID id, @Bind("workspaceId") String workspaceId);

    @SqlQuery("SELECT * FROM endpoints WHERE id IN (<ids>) AND workspace_id = :workspaceId")
    List<Endpoint> findByIds(@BindList("ids") Set<UUID> ids, @Bind("workspaceId") String workspaceId);

    @SqlQuery("SELECT COUNT(*) FROM endpoints "
            + " WHERE workspace_id = :workspaceId AND project_id = :projectId "
            + " <if(name)> AND name like concat('%', :name, '%') <endif> ")
    @UseStringTemplateEngine
    @AllowUnusedBindings
    long findCount(@Bind("workspaceId") String workspaceId, @Bind("projectId") UUID projectId,
            @Define("name") @Bind("name") String name);

    @SqlQuery("SELECT * FROM endpoints "
            + " WHERE workspace_id = :workspaceId AND project_id = :projectId "
            + " <if(name)> AND name like concat('%', :name, '%') <endif> "
            + " ORDER BY <if(sort_fields)> <sort_fields>, <endif> id DESC "
            + " LIMIT :limit OFFSET :offset ")
    @UseStringTemplateEngine
    @AllowUnusedBindings
    List<Endpoint> find(@Bind("limit") int limit, @Bind("offset") int offset, @Bind("workspaceId") String workspaceId,
            @Bind("projectId") UUID projectId, @Define("name") @Bind("name") String name,
            @Define("sort_fields") @Bind("sort_fields") String sortingFields);

    default Optional<Endpoint> fetch(UUID id, String workspaceId) {
        return Optional.ofNullable(findById(id, workspaceId));
    }
}

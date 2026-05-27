package com.comet.opik.domain;

import com.comet.opik.api.Environment;
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

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@RegisterConstructorMapper(Environment.class)
@RegisterArgumentFactory(UUIDArgumentFactory.class)
interface EnvironmentDAO {

    @SqlUpdate("INSERT INTO environments (id, workspace_id, name, description, color, position, created_by, last_updated_by) "
            +
            "VALUES (:bean.id, :workspaceId, :bean.name, :bean.description, COALESCE(:bean.color, 'default'), COALESCE(:bean.position, 0), :bean.createdBy, :bean.lastUpdatedBy)")
    void save(@Bind("workspaceId") String workspaceId, @BindMethods("bean") Environment environment);

    @SqlBatch("INSERT IGNORE INTO environments (id, workspace_id, name, color, position, created_by, last_updated_by) "
            +
            "VALUES (:bean.id, :workspaceId, :bean.name, COALESCE(:bean.color, 'default'), 0, :userName, :userName)")
    void saveBatch(@Bind("workspaceId") String workspaceId, @Bind("userName") String userName,
            @BindMethods("bean") List<Environment> environments);

    @SqlUpdate("UPDATE environments SET " +
            "name = COALESCE(:name, name), " +
            "description = COALESCE(:description, description), " +
            "color = COALESCE(:color, color), " +
            "position = COALESCE(:position, position), " +
            "last_updated_by = :lastUpdatedBy " +
            "WHERE id = :id AND workspace_id = :workspaceId")
    void update(@Bind("id") UUID id,
            @Bind("workspaceId") String workspaceId,
            @Bind("name") String name,
            @Bind("description") String description,
            @Bind("color") String color,
            @Bind("position") Integer position,
            @Bind("lastUpdatedBy") String lastUpdatedBy);

    @SqlUpdate("DELETE FROM environments WHERE id IN (<ids>) AND workspace_id = :workspaceId")
    void delete(@BindList("ids") Set<UUID> ids, @Bind("workspaceId") String workspaceId);

    @SqlQuery("SELECT * FROM environments WHERE id = :id AND workspace_id = :workspaceId")
    Environment findById(@Bind("id") UUID id, @Bind("workspaceId") String workspaceId);

    @SqlQuery("SELECT * FROM environments WHERE workspace_id = :workspaceId ORDER BY created_at ASC")
    List<Environment> findAll(@Bind("workspaceId") String workspaceId);

    @SqlQuery("SELECT name FROM environments WHERE workspace_id = :workspaceId <if(names)> AND name IN (<names>) <endif>")
    @UseStringTemplateEngine
    @AllowUnusedBindings
    Set<String> findNames(@Bind("workspaceId") String workspaceId,
            @Define("names") @BindList(onEmpty = BindList.EmptyHandling.NULL_VALUE, value = "names") Set<String> names);

    default Set<String> findAllNames(String workspaceId) {
        return findNames(workspaceId, null);
    }

    default Set<String> findExistingNames(String workspaceId, Set<String> names) {
        return findNames(workspaceId, names);
    }

    @SqlQuery("SELECT COUNT(*) FROM environments WHERE workspace_id = :workspaceId AND name = :name")
    long countByName(@Bind("workspaceId") String workspaceId, @Bind("name") String name);

    @SqlQuery("SELECT COUNT(*) FROM environments WHERE workspace_id = :workspaceId")
    long countByWorkspace(@Bind("workspaceId") String workspaceId);

    default Optional<Environment> fetch(UUID id, String workspaceId) {
        return Optional.ofNullable(findById(id, workspaceId));
    }
}

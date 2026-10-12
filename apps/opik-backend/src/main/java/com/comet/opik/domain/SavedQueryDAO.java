package com.comet.opik.domain;

import com.comet.opik.api.SavedQuery;
import com.comet.opik.infrastructure.db.UUIDArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindList;
import org.jdbi.v3.sqlobject.customizer.BindMethods;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@RegisterArgumentFactory(UUIDArgumentFactory.class)
@RegisterConstructorMapper(SavedQuery.class)
interface SavedQueryDAO {

    @SqlUpdate("INSERT INTO saved_queries(id, workspace_id, project_id, `sql`, created_by) "
            + "VALUES (:query.id, :workspaceId, :query.projectId, :query.sql, :query.createdBy)")
    void save(@BindMethods("query") SavedQuery query, @Bind("workspaceId") String workspaceId);

    @SqlQuery("SELECT id, project_id, `sql`, created_at, created_by FROM saved_queries "
            + "WHERE id = :id AND workspace_id = :workspaceId")
    Optional<SavedQuery> findById(@Bind("id") UUID id, @Bind("workspaceId") String workspaceId);

    @SqlQuery("SELECT id FROM saved_queries WHERE workspace_id = :workspaceId AND id IN (<ids>)")
    Set<UUID> findExistingIds(@BindList("ids") Collection<UUID> ids, @Bind("workspaceId") String workspaceId);
}

package com.comet.opik.domain;

import com.comet.opik.api.Prompt;
import com.comet.opik.infrastructure.db.UUIDArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindMethods;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

import java.util.UUID;

@RegisterConstructorMapper(Prompt.class)
@RegisterArgumentFactory(UUIDArgumentFactory.class)
interface PromptDAO {

    @SqlUpdate("INSERT INTO prompts (id, name, description, created_by, last_updated_by, workspace_id) " +
            "VALUES (:bean.id, :bean.name, :bean.description, :bean.createdBy, :bean.lastUpdatedBy, :workspaceId)")
    void save(@Bind("workspaceId") String workspaceId, @BindMethods("bean") Prompt prompt);

    @SqlQuery("SELECT * FROM prompts WHERE id = :id AND workspace_id = :workspaceId")
    Prompt findById(@Bind("id") UUID id, @Bind("workspaceId") String workspaceId);

}

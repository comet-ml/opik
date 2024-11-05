package com.comet.opik.domain;

import com.comet.opik.api.PromptVersion;
import com.comet.opik.infrastructure.db.UUIDArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindMethods;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

import java.util.UUID;

@RegisterArgumentFactory(UUIDArgumentFactory.class)
@RegisterConstructorMapper(PromptVersion.class)
interface PromptVersionDAO {

    @SqlUpdate("INSERT INTO prompt_versions (id, prompt_id, commit, template, created_by, workspace_id) " +
            "VALUES (:bean.id, :bean.promptId, :bean.commit, :bean.template, :bean.createdBy, :workspace_id)")
    void save(@Bind("workspace_id") String workspaceId, @BindMethods("bean") PromptVersion prompt);

    @SqlQuery("SELECT * FROM prompt_versions WHERE id = :id AND workspace_id = :workspace_id")
    PromptVersion findById(@Bind("id") UUID id, @Bind("workspace_id") String workspaceId);

    @SqlUpdate("DELETE FROM prompt_versions WHERE prompt_id = :prompt_id AND workspace_id = :workspace_id")
    int deleteByPromptId(@Bind("prompt_id") UUID promptId, @Bind("workspace_id") String workspaceId);
}

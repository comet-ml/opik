package com.comet.opik.domain;

import com.comet.opik.api.PromptVersion;
import com.comet.opik.infrastructure.db.UUIDArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindList;
import org.jdbi.v3.sqlobject.customizer.BindMethods;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@RegisterArgumentFactory(UUIDArgumentFactory.class)
@RegisterConstructorMapper(PromptVersion.class)
@RegisterConstructorMapper(PromptVersionInfo.class)
interface PromptVersionDAO {

    @SqlUpdate("INSERT INTO prompt_versions (id, prompt_id, commit, template, metadata, change_description, type, created_by, workspace_id) "
            +
            "VALUES (:bean.id, :bean.promptId, :bean.commit, :bean.template, :bean.metadata, :bean.changeDescription, :bean.type, :bean.createdBy, :workspace_id)")
    void save(@Bind("workspace_id") String workspaceId, @BindMethods("bean") PromptVersion prompt);

    @SqlQuery("SELECT * FROM prompt_versions WHERE id IN (<ids>) AND workspace_id = :workspace_id")
    List<PromptVersion> findByIds(@BindList("ids") Collection<UUID> ids, @Bind("workspace_id") String workspaceId);

    @SqlQuery("SELECT count(id) FROM prompt_versions WHERE prompt_id = :prompt_id AND workspace_id = :workspace_id")
    long countByPromptId(@Bind("prompt_id") UUID promptId, @Bind("workspace_id") String workspaceId);

    @SqlQuery("SELECT * FROM prompt_versions WHERE prompt_id = :prompt_id AND workspace_id = :workspace_id ORDER BY id DESC LIMIT :limit OFFSET :offset")
    List<PromptVersion> findByPromptId(@Bind("prompt_id") UUID promptId, @Bind("workspace_id") String workspaceId,
            @Bind("limit") int limit, @Bind("offset") int offset);

    @SqlQuery("SELECT * FROM prompt_versions WHERE prompt_id = :prompt_id AND commit = :commit AND workspace_id = :workspace_id")
    PromptVersion findByCommit(@Bind("prompt_id") UUID promptId, @Bind("commit") String commit,
            @Bind("workspace_id") String workspaceId);

    @SqlUpdate("DELETE FROM prompt_versions WHERE prompt_id = :prompt_id AND workspace_id = :workspace_id")
    int deleteByPromptId(@Bind("prompt_id") UUID promptId, @Bind("workspace_id") String workspaceId);

    @SqlQuery("""
            SELECT pv.id, pv.commit, p.name AS prompt_name
            FROM prompt_versions pv
            INNER JOIN prompts p ON pv.prompt_id = p.id
            WHERE pv.id IN (<ids>) AND pv.workspace_id = :workspace_id
            """)
    List<PromptVersionInfo> findPromptVersionInfoByVersionsIds(@BindList("ids") Set<UUID> ids,
            @Bind("workspace_id") String workspaceId);
}

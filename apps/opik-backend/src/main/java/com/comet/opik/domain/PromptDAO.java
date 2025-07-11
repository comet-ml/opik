package com.comet.opik.domain;

import com.comet.opik.api.Prompt;
import com.comet.opik.infrastructure.db.PromptVersionColumnMapper;
import com.comet.opik.infrastructure.db.SetFlatArgumentFactory;
import com.comet.opik.infrastructure.db.UUIDArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterColumnMapper;
import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.AllowUnusedBindings;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindList;
import org.jdbi.v3.sqlobject.customizer.BindMap;
import org.jdbi.v3.sqlobject.customizer.BindMethods;
import org.jdbi.v3.sqlobject.customizer.Define;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;
import org.jdbi.v3.stringtemplate4.UseStringTemplateEngine;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@RegisterColumnMapper(PromptVersionColumnMapper.class)
@RegisterConstructorMapper(Prompt.class)
@RegisterArgumentFactory(UUIDArgumentFactory.class)
@RegisterArgumentFactory(SetFlatArgumentFactory.class)
@RegisterColumnMapper(SetFlatArgumentFactory.class)
interface PromptDAO {

    @SqlUpdate("INSERT INTO prompts (id, name, description, created_by, last_updated_by, workspace_id, tags) " +
            "VALUES (:bean.id, :bean.name, :bean.description, :bean.createdBy, :bean.lastUpdatedBy, :workspace_id, :bean.tags)")
    void save(@Bind("workspace_id") String workspaceId, @BindMethods("bean") Prompt prompt);

    @SqlQuery("""
            SELECT
                *,
                (
                    SELECT COUNT(pv.id)
                    FROM prompt_versions pv
                    WHERE pv.prompt_id = p.id
                    AND pv.workspace_id = p.workspace_id
                ) AS version_count,
                (
                    SELECT JSON_OBJECT(
                        'id', pv.id,
                        'prompt_id', pv.prompt_id,
                        'commit', pv.commit,
                        'template', pv.template,
                        'metadata', pv.metadata,
                        'change_description', pv.change_description,
                        'type', pv.type,
                        'created_at', pv.created_at,
                        'created_by', pv.created_by,
                        'last_updated_at', pv.last_updated_at,
                        'last_updated_by', pv.last_updated_by
                    )
                    FROM prompt_versions pv
                    WHERE pv.prompt_id = p.id
                    AND pv.workspace_id = p.workspace_id
                    ORDER BY pv.id DESC
                    LIMIT 1
                ) AS latest_version
            FROM prompts p
            WHERE id = :id
            AND workspace_id = :workspace_id
            """)
    Prompt findById(@Bind("id") UUID id, @Bind("workspace_id") String workspaceId);

    @SqlQuery("""
                SELECT
                    p.*,
                    count(pv.id) as version_count
                FROM prompts p
                LEFT JOIN prompt_versions pv ON pv.prompt_id = p.id
                WHERE p.workspace_id = :workspace_id
                <if(filters)> AND <filters> <endif>
                <if(name)> AND p.name like concat('%', :name, '%') <endif>
                GROUP BY p.id
                ORDER BY <if(sort_fields)> <sort_fields>, <endif> p.id DESC
                LIMIT :limit OFFSET :offset
            """)
    @UseStringTemplateEngine
    @AllowUnusedBindings
    List<Prompt> find(@Define("name") @Bind("name") String name, @Bind("workspace_id") String workspaceId,
            @Bind("offset") int offset, @Bind("limit") int limit,
            @Define("sort_fields") @Bind("sort_fields") String sortingFields,
            @Define("filters") String filters,
            @BindMap Map<String, Object> filterMapping);

    @SqlQuery("SELECT COUNT(id) FROM prompts " +
            " WHERE workspace_id = :workspace_id " +
            "<if(filters)> AND <filters> <endif>" +
            " <if(name)> AND name like concat('%', :name, '%') <endif> ")
    @UseStringTemplateEngine
    @AllowUnusedBindings
    long count(@Define("name") @Bind("name") String name, @Bind("workspace_id") String workspaceId,
            @Define("filters") String filters,
            @BindMap Map<String, Object> filterMapping);

    @SqlQuery("SELECT * FROM prompts WHERE name = :name AND workspace_id = :workspace_id")
    Prompt findByName(@Bind("name") String name, @Bind("workspace_id") String workspaceId);

    @SqlUpdate("UPDATE prompts SET name = :bean.name, description = :bean.description, last_updated_by = :bean.lastUpdatedBy "
            +
            " <if(tags)>, tags = :tags <endif> " +
            " WHERE id = :bean.id AND workspace_id = :workspace_id")
    @UseStringTemplateEngine
    @AllowUnusedBindings
    int update(@Bind("workspace_id") String workspaceId, @BindMethods("bean") Prompt updatedPrompt,
            @Define("tags") @Bind("tags") Set<String> tags);

    @SqlUpdate("DELETE FROM prompts WHERE id = :id AND workspace_id = :workspace_id")
    int delete(@Bind("id") UUID id, @Bind("workspace_id") String workspaceId);

    @SqlUpdate("DELETE FROM prompts WHERE id IN (<ids>) AND workspace_id = :workspaceId")
    void delete(@BindList("ids") Set<UUID> ids, @Bind("workspaceId") String workspaceId);
}

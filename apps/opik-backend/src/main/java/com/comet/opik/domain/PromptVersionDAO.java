package com.comet.opik.domain;

import com.comet.opik.api.PromptVersion;
import com.comet.opik.api.PromptVersionBatchUpdate;
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

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@RegisterArgumentFactory(UUIDArgumentFactory.class)
@RegisterArgumentFactory(SetFlatArgumentFactory.class)
@RegisterColumnMapper(SetFlatArgumentFactory.class)
@RegisterConstructorMapper(PromptVersion.class)
@RegisterConstructorMapper(PromptVersionInfo.class)
interface PromptVersionDAO {

    @SqlUpdate("""
            INSERT INTO prompt_versions (
                id,
                prompt_id,
                commit,
                template,
                metadata,
                change_description,
                type,
                tags,
                created_by,
                workspace_id
            )
            VALUES (
                :bean.id,
                :bean.promptId,
                :bean.commit,
                :bean.template,
                :bean.metadata,
                :bean.changeDescription,
                :bean.type,
                :bean.tags,
                :bean.createdBy,
                :workspace_id
            )
            """)
    void save(@Bind("workspace_id") String workspaceId, @BindMethods("bean") PromptVersion prompt);

    @SqlQuery("""
            SELECT pv.*, p.template_structure
            FROM prompt_versions pv
            INNER JOIN prompts p ON pv.prompt_id = p.id
            WHERE pv.workspace_id = :workspace_id
            <if(ids)> AND pv.id IN (<ids>) <endif>
            <if(prompt_id)> AND pv.prompt_id = :prompt_id <endif>
            <if(search)> AND (pv.template LIKE CONCAT('%', :search, '%') OR pv.change_description LIKE CONCAT('%', :search, '%')) <endif>
            <if(filters)> AND <filters> <endif>
            ORDER BY <if(sort_fields)><sort_fields>, <endif>pv.id DESC
            <if(limit)> LIMIT :limit OFFSET :offset <endif>
            """)
    @UseStringTemplateEngine
    @AllowUnusedBindings
    List<PromptVersion> find(
            @Bind("workspace_id") String workspaceId,
            @Define("ids") @BindList(onEmpty = BindList.EmptyHandling.NULL_VALUE, value = "ids") Collection<UUID> ids,
            @Define("prompt_id") @Bind("prompt_id") UUID promptId,
            @Define("search") @Bind("search") String search,
            @Define("offset") @Bind("offset") Integer offset,
            @Define("limit") @Bind("limit") Integer limit,
            @Define("sort_fields") String sortingFields,
            @Define("filters") String filters,
            @BindMap Map<String, Object> filterMapping);

    default List<PromptVersion> find(
            String workspaceId,
            UUID promptId,
            Integer offset,
            Integer limit) {
        return find(workspaceId, null, promptId, null, offset, limit, null, null, Map.of());
    }

    default List<PromptVersion> find(
            String workspaceId,
            UUID promptId,
            String search,
            Integer offset,
            Integer limit,
            String sortingFields,
            String filters,
            Map<String, Object> filterMapping) {
        return find(workspaceId, null, promptId, search, offset, limit, sortingFields, filters, filterMapping);
    }

    default List<PromptVersion> findByIds(Collection<UUID> ids, String workspaceId) {
        return find(workspaceId, ids, null, null, null, null, null, null, Map.of());
    }

    @SqlQuery("""
            SELECT COUNT(DISTINCT pv.id)
            FROM prompt_versions pv
            WHERE pv.workspace_id = :workspace_id
            <if(ids)> AND pv.id IN (<ids>) <endif>
            <if(prompt_id)> AND pv.prompt_id = :prompt_id <endif>
            <if(search)> AND (pv.template LIKE CONCAT('%', :search, '%') OR pv.change_description LIKE CONCAT('%', :search, '%')) <endif>
            <if(filters)> AND <filters> <endif>
            """)
    @UseStringTemplateEngine
    @AllowUnusedBindings
    long findCount(
            @Bind("workspace_id") String workspaceId,
            @Define("ids") @BindList(onEmpty = BindList.EmptyHandling.NULL_VALUE, value = "ids") Collection<UUID> ids,
            @Define("prompt_id") @Bind("prompt_id") UUID promptId,
            @Define("search") @Bind("search") String search,
            @Define("filters") String filters,
            @BindMap Map<String, Object> filterMapping);

    default long findCount(
            String workspaceId,
            UUID promptId,
            String search,
            String filters,
            Map<String, Object> filterMapping) {
        return findCount(workspaceId, null, promptId, search, filters, filterMapping);
    }

    @SqlQuery("""
            SELECT pv.*, p.template_structure
            FROM prompt_versions pv
            INNER JOIN prompts p ON pv.prompt_id = p.id
            WHERE pv.prompt_id = :prompt_id AND pv.commit = :commit AND pv.workspace_id = :workspace_id
            """)
    PromptVersion findByCommit(@Bind("prompt_id") UUID promptId, @Bind("commit") String commit,
            @Bind("workspace_id") String workspaceId);

    /**
     * Batch update for multiple prompt versions in a single database operation.
     *
     * <p><strong>PATCH Semantics:</strong></p>
     * <ul>
     *   <li>null: preserves existing values (no change) - allows updating other fields</li>
     *   <li>empty: clears values</li>
     * </ul>
     *
     * <p><strong>Tags Replace Mode (mergeTags = false):</strong></p>
     * <ul>
     *   <li>Replaces all existing tags with the provided tags</li>
     *   <li>Preserve tags when null is passed</li>
     * </ul>
     *
     * <p><strong>Tags Merge Mode (mergeTags = true):</strong></p>
     * <ul>
     *   <li>Combines existing tags with new tags</li>
     *   <li>Handles null tags gracefully</li>
     *   <li>Duplicates are handled when read into Set via JSON deserialization (same as in ClickHouse DAOs)</li>
     *   <li>Tags are stored as JSON arrays, e.g., ["tag1", "tag2"]</li>
     * </ul>
     *
     * @param workspaceId Workspace ID for security filtering
     * @param ids         Set of prompt version IDs to update
     * @param bean        Batch update request containing updates to apply
     * @param mergeTags   Whether to merge tags (true) or replace them (false)
     * @return Number of rows updated
     */
    @SqlUpdate("""
            UPDATE prompt_versions
            SET tags =
                <if(merge_tags)>
                    JSON_MERGE_PRESERVE(COALESCE(tags, '[]'), COALESCE(:bean.update.tags, '[]'))
                <else>
                    COALESCE(:bean.update.tags, tags)
                <endif>
            WHERE workspace_id = :workspace_id
            AND id IN (<ids>)
            """)
    @UseStringTemplateEngine
    @AllowUnusedBindings
    int update(@Bind("workspace_id") String workspaceId,
            @BindList("ids") Set<UUID> ids,
            @BindMethods("bean") PromptVersionBatchUpdate bean,
            @Define("merge_tags") boolean mergeTags);

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

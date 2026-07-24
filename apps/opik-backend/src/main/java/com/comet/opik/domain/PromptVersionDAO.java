package com.comet.opik.domain;

import com.comet.opik.api.PromptVersion;
import com.comet.opik.api.PromptVersionBatchUpdate;
import com.comet.opik.infrastructure.db.SetFlatArgumentFactory;
import com.comet.opik.infrastructure.db.UUIDArgumentFactory;
import org.apache.commons.collections4.CollectionUtils;
import org.jdbi.v3.sqlobject.config.RegisterArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterColumnMapper;
import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.AllowUnusedBindings;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindList;
import org.jdbi.v3.sqlobject.customizer.BindMap;
import org.jdbi.v3.sqlobject.customizer.BindMethods;
import org.jdbi.v3.sqlobject.customizer.Define;
import org.jdbi.v3.sqlobject.statement.SqlBatch;
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
                version_number,
                template,
                metadata,
                change_description,
                type,
                version_type,
                tags,
                created_by,
                workspace_id
            )
            VALUES (
                :bean.id,
                :bean.promptId,
                :bean.commit,
                :bean.versionNumber,
                :bean.template,
                :bean.metadata,
                :bean.changeDescription,
                :bean.type,
                :bean.versionType,
                :bean.tags,
                :bean.createdBy,
                :workspace_id
            )
            """)
    void save(@Bind("workspace_id") String workspaceId, @BindMethods("bean") PromptVersion prompt);

    @SqlBatch("""
            INSERT INTO prompt_version_envs (id, workspace_id, prompt_id, version_id, environment, created_by)
            VALUES (:id, :workspace_id, :prompt_id, :version_id, :environment, :created_by)
            """)
    void saveEnvironments(
            @Bind("id") List<UUID> ids,
            @Bind("workspace_id") String workspaceId,
            @Bind("prompt_id") UUID promptId,
            @Bind("version_id") UUID versionId,
            @Bind("environment") List<String> environments,
            @Bind("created_by") String createdBy);

    default void saveEnvironments(List<UUID> ids, String workspaceId, UUID promptId, UUID versionId,
            Set<String> environments, String createdBy) {
        if (CollectionUtils.isEmpty(environments)) {
            return;
        }
        saveEnvironments(ids, workspaceId, promptId, versionId, List.copyOf(environments), createdBy);
    }

    @SqlQuery("""
            SELECT environment FROM prompt_version_envs
            WHERE workspace_id = :workspace_id
            <if(version_id)> AND version_id = :version_id <endif>
            <if(prompt_id)> AND prompt_id = :prompt_id <endif>
            <if(environments)> AND environment IN (<environments>) <endif>
            AND ended_at IS NULL
            """)
    @UseStringTemplateEngine
    @AllowUnusedBindings
    Set<String> findActiveEnvironments(
            @Bind("workspace_id") String workspaceId,
            @Define("version_id") @Bind("version_id") UUID versionId,
            @Define("prompt_id") @Bind("prompt_id") UUID promptId,
            @Define("environments") @BindList(onEmpty = BindList.EmptyHandling.NULL_VALUE, value = "environments") Set<String> environments);

    default Set<String> findVersionEnvironments(UUID versionId, String workspaceId) {
        return findActiveEnvironments(workspaceId, versionId, null, null);
    }

    default Set<String> findTakenEnvironments(UUID promptId, String workspaceId, Set<String> environments) {
        return findActiveEnvironments(workspaceId, null, promptId, environments);
    }

    @SqlUpdate("""
            UPDATE prompt_version_envs
            SET ended_at = CURRENT_TIMESTAMP(6)
            WHERE workspace_id = :workspace_id
            <if(version_id)> AND version_id = :version_id <endif>
            <if(prompt_id)> AND prompt_id = :prompt_id <endif>
            <if(environments)> AND environment IN (<environments>) <endif>
            AND ended_at IS NULL
            """)
    @UseStringTemplateEngine
    @AllowUnusedBindings
    void closeEnvironmentAssignments(
            @Bind("workspace_id") String workspaceId,
            @Define("version_id") @Bind("version_id") UUID versionId,
            @Define("prompt_id") @Bind("prompt_id") UUID promptId,
            @Define("environments") @BindList(onEmpty = BindList.EmptyHandling.NULL_VALUE, value = "environments") Set<String> environments);

    default void closeVersionEnvironmentsForNames(UUID versionId, String workspaceId, Set<String> environments) {
        closeEnvironmentAssignments(workspaceId, versionId, null, environments);
    }

    default void closeEnvOwnershipsForPrompt(UUID promptId, String workspaceId, Set<String> environments) {
        closeEnvironmentAssignments(workspaceId, null, promptId, environments);
    }

    @SqlQuery("""
            SELECT pv.*,
                p.template_structure,
                (
                    SELECT JSON_ARRAYAGG(pve.environment)
                    FROM prompt_version_envs pve
                    WHERE pve.version_id = pv.id
                        AND pve.workspace_id = :workspace_id
                        AND pve.ended_at IS NULL
                ) AS environments
            FROM prompt_versions pv
            INNER JOIN prompts p ON pv.prompt_id = p.id
            WHERE pv.workspace_id = :workspace_id
            <if(ids)> AND pv.id IN (<ids>) <endif>
            <if(prompt_id)> AND pv.prompt_id = :prompt_id AND pv.version_type = 'prompt_version' <endif>
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
            <if(prompt_id)> AND pv.prompt_id = :prompt_id AND pv.version_type = 'prompt_version' <endif>
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
            SELECT pv.*,
                p.template_structure,
                (SELECT JSON_ARRAYAGG(pve.environment) FROM prompt_version_envs pve WHERE pve.workspace_id = pv.workspace_id AND pve.version_id = pv.id AND pve.ended_at IS NULL) AS environments
            FROM prompt_versions pv
            INNER JOIN prompts p ON pv.prompt_id = p.id
            <if(environment)>
            INNER JOIN prompt_version_envs pve ON pve.workspace_id = pv.workspace_id AND pve.version_id = pv.id AND pve.environment = :environment AND pve.ended_at IS NULL
            <endif>
            WHERE pv.prompt_id = :prompt_id AND pv.workspace_id = :workspace_id
            AND pv.version_type = 'prompt_version'
            <if(commit)> AND pv.commit = :commit <endif>
            <if(version_number)> AND pv.version_number = :version_number <endif>
            """)
    @UseStringTemplateEngine
    @AllowUnusedBindings
    PromptVersion findSingleVersion(
            @Bind("prompt_id") UUID promptId,
            @Bind("workspace_id") String workspaceId,
            @Define("commit") @Bind("commit") String commit,
            @Define("version_number") @Bind("version_number") String versionNumber,
            @Define("environment") @Bind("environment") String environment);

    default PromptVersion findByCommit(UUID promptId, String commit, String workspaceId) {
        return findSingleVersion(promptId, workspaceId, commit, null, null);
    }

    default PromptVersion findByVersionNumber(UUID promptId, String versionNumber, String workspaceId) {
        return findSingleVersion(promptId, workspaceId, null, versionNumber, null);
    }

    @SqlQuery("""
            SELECT COALESCE(MAX(CAST(SUBSTRING(version_number, 2) AS UNSIGNED)), 0)
            FROM prompt_versions
            WHERE workspace_id = :workspace_id
            AND prompt_id = :prompt_id
            AND version_type = 'prompt_version'
            """)
    int findMaxVersionNumber(@Bind("workspace_id") String workspaceId, @Bind("prompt_id") UUID promptId);

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

    default PromptVersion findByEnvironment(UUID promptId, String environment, String workspaceId) {
        return findSingleVersion(promptId, workspaceId, null, null, environment);
    }

    @SqlQuery("""
            SELECT pv.id, pv.commit, pv.version_number, p.name AS prompt_name
            FROM prompt_versions pv
            INNER JOIN prompts p ON pv.prompt_id = p.id
            WHERE pv.id IN (<ids>) AND pv.workspace_id = :workspace_id
            """)
    List<PromptVersionInfo> findPromptVersionInfoByVersionsIds(@BindList("ids") Set<UUID> ids,
            @Bind("workspace_id") String workspaceId);
}

package com.comet.opik.domain;

import com.comet.opik.api.Prompt;
import com.comet.opik.api.PromptVersionLink;
import com.comet.opik.infrastructure.db.PromptVersionColumnMapper;
import com.comet.opik.infrastructure.db.PromptVersionLinkRowMapper;
import com.comet.opik.infrastructure.db.SetFlatArgumentFactory;
import com.comet.opik.infrastructure.db.UUIDArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterColumnMapper;
import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.config.RegisterRowMapper;
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

@RegisterColumnMapper(PromptVersionColumnMapper.class)
@RegisterConstructorMapper(Prompt.class)
@RegisterArgumentFactory(UUIDArgumentFactory.class)
@RegisterArgumentFactory(SetFlatArgumentFactory.class)
@RegisterColumnMapper(SetFlatArgumentFactory.class)
public interface PromptDAO {

    /**
     * Checks for V1 (workspace-scoped) prompts excluding known demo names.
     * MySQL utf8mb4_unicode_ci collation makes the NOT IN comparison case-insensitive,
     * so demo name variants differing only in casing are automatically excluded.
     */
    @SqlQuery("""
            SELECT EXISTS(
                SELECT 1 FROM prompts
                WHERE workspace_id = :workspaceId AND project_id IS NULL
                AND name NOT IN (<demoPromptNames>)
            )""")
    @UseStringTemplateEngine
    @AllowUnusedBindings
    boolean hasVersion1Prompts(
            @Bind("workspaceId") String workspaceId, @BindList("demoPromptNames") List<String> demoPromptNames);

    @SqlUpdate("INSERT INTO prompts (id, name, description, created_by, last_updated_by, workspace_id, project_id, tags, template_structure) "
            +
            "VALUES (:bean.id, :bean.name, :bean.description, :bean.createdBy, :bean.lastUpdatedBy, :workspace_id, :bean.projectId, :bean.tags, :bean.templateStructure)")
    void save(@Bind("workspace_id") String workspaceId, @BindMethods("bean") Prompt prompt);

    @SqlQuery("""
            WITH pv_for_prompt AS (
                SELECT pv.*
                FROM prompt_versions pv
                WHERE pv.prompt_id = :id AND pv.workspace_id = :workspace_id
            ), active_envs AS (
                SELECT pve.version_id, pve.environment
                FROM prompt_version_envs pve
                INNER JOIN pv_for_prompt pfp ON pfp.id = pve.version_id
                WHERE pve.workspace_id = :workspace_id AND pve.ended_at IS NULL
            ), ver_envs AS (
                SELECT version_id, JSON_ARRAYAGG(environment) AS environments
                FROM active_envs
                GROUP BY version_id
            )
            SELECT
                p.*,
                (
                    SELECT COUNT(pfp.id)
                    FROM pv_for_prompt pfp
                    WHERE pfp.version_type = 'prompt_version'
                ) AS version_count,
                (
                    SELECT JSON_OBJECT(
                        'id', pfp.id,
                        'prompt_id', pfp.prompt_id,
                        'commit', pfp.commit,
                        'version_number', pfp.version_number,
                        'template', pfp.template,
                        'metadata', pfp.metadata,
                        'change_description', pfp.change_description,
                        'type', pfp.type,
                        'version_type', pfp.version_type,
                        'environments', ve.environments,
                        'tags', pfp.tags,
                        'created_at', pfp.created_at,
                        'created_by', pfp.created_by,
                        'last_updated_at', pfp.last_updated_at,
                        'last_updated_by', pfp.last_updated_by
                    )
                    FROM pv_for_prompt pfp
                    LEFT JOIN ver_envs ve ON ve.version_id = pfp.id
                    WHERE pfp.version_type = 'prompt_version'
                    ORDER BY pfp.id DESC
                    LIMIT 1
                ) AS latest_version
                <if(mask_id || environment)>
                ,
                (
                    SELECT JSON_OBJECT(
                        'id', pfp.id,
                        'prompt_id', pfp.prompt_id,
                        'commit', pfp.commit,
                        'version_number', pfp.version_number,
                        'template', pfp.template,
                        'metadata', pfp.metadata,
                        'change_description', pfp.change_description,
                        'type', pfp.type,
                        'version_type', pfp.version_type,
                        'environments', ve.environments,
                        'tags', pfp.tags,
                        'created_at', pfp.created_at,
                        'created_by', pfp.created_by,
                        'last_updated_at', pfp.last_updated_at,
                        'last_updated_by', pfp.last_updated_by
                    )
                    FROM pv_for_prompt pfp
                    LEFT JOIN ver_envs ve ON ve.version_id = pfp.id
                    WHERE 1=1
                    <if(mask_id)> AND pfp.id = :mask_id AND pfp.version_type = 'mask' <endif>
                    <if(environment)> AND pfp.id IN (SELECT version_id FROM active_envs WHERE environment = :environment) AND pfp.version_type = 'prompt_version' <endif>
                ) AS requested_version
                <endif>
            FROM prompts p
            WHERE p.id = :id
            AND p.workspace_id = :workspace_id
            """)
    @UseStringTemplateEngine
    @AllowUnusedBindings
    Prompt findById(@Bind("id") UUID id, @Bind("workspace_id") String workspaceId,
            @Define("mask_id") @Bind("mask_id") UUID maskId,
            @Define("environment") @Bind("environment") String environment);

    default Prompt findById(UUID id, String workspaceId) {
        return findById(id, workspaceId, null, null);
    }

    default Prompt findById(UUID id, String workspaceId, UUID maskId) {
        return findById(id, workspaceId, maskId, null);
    }

    @SqlQuery("""
            SELECT
                *
            FROM (
                SELECT
                  p.*,
                  (
                    SELECT COUNT(pv.id)
                      FROM prompt_versions pv
                     WHERE pv.workspace_id = p.workspace_id
                     AND pv.prompt_id = p.id
                     AND pv.version_type = 'prompt_version'
                  ) AS version_count
                FROM prompts AS p
                WHERE workspace_id = :workspace_id
                <if(name)> AND name like concat('%', :name, '%') <endif>
                <if(project_id)> AND project_id = :project_id <endif>
            ) AS prompt_full
            <if(filters)> WHERE <filters> <endif>
            ORDER BY <if(sort_fields)> <sort_fields>, <endif> id DESC
            LIMIT :limit OFFSET :offset
            """)
    @UseStringTemplateEngine
    @AllowUnusedBindings
    List<Prompt> find(@Define("name") @Bind("name") String name, @Bind("workspace_id") String workspaceId,
            @Define("project_id") @Bind("project_id") UUID projectId,
            @Bind("offset") int offset, @Bind("limit") int limit,
            @Define("sort_fields") @Bind("sort_fields") String sortingFields,
            @Define("filters") String filters,
            @BindMap Map<String, Object> filterMapping);

    @SqlQuery("""
            WITH selected_prompts AS (
            	SELECT
                  p.*,
                  (
                    SELECT COUNT(pv.id)
                      FROM prompt_versions pv
                      WHERE pv.workspace_id = p.workspace_id
                      AND pv.prompt_id = p.id
                      AND pv.version_type = 'prompt_version'
                  ) AS version_count
            	FROM prompts AS p
            	WHERE workspace_id = :workspace_id
            	<if(ids)> AND id IN (<ids>) <endif>
            ), pv_ranked AS (
                SELECT pv.*,
                    ROW_NUMBER() OVER (PARTITION BY pv.prompt_id ORDER BY pv.id DESC) AS rn
                FROM prompt_versions pv
                WHERE pv.workspace_id = :workspace_id AND pv.version_type = 'prompt_version'
                <if(ids)> AND pv.prompt_id IN (<ids>) <endif>
            ), active_envs AS (
                SELECT pve.version_id, pve.environment
                FROM prompt_version_envs pve
                INNER JOIN pv_ranked pvr ON pvr.id = pve.version_id AND pvr.rn = 1
                WHERE pve.workspace_id = :workspace_id AND pve.ended_at IS NULL
            ), ver_envs AS (
                SELECT version_id, JSON_ARRAYAGG(environment) AS environments
                FROM active_envs
                GROUP BY version_id
            ), latest_versions AS (
            	SELECT
              JSON_OBJECT(
                'id', pvr.id,
                'prompt_id', pvr.prompt_id,
                'commit', pvr.commit,
                'version_number', pvr.version_number,
                'template', pvr.template,
                'metadata', pvr.metadata,
                'change_description', pvr.change_description,
                'type', pvr.type,
                'version_type', pvr.version_type,
                'environments', ve.environments,
                'tags', pvr.tags,
                'created_at', pvr.created_at,
                'created_by', pvr.created_by,
                'last_updated_at', pvr.last_updated_at,
                'last_updated_by', pvr.last_updated_by
              ) AS latest_version,
              pvr.prompt_id
              FROM pv_ranked pvr
              LEFT JOIN ver_envs ve ON ve.version_id = pvr.id
              WHERE pvr.rn = 1
            )
            SELECT sp.*, lv.latest_version
            FROM selected_prompts sp
            LEFT JOIN latest_versions lv
            ON sp.id = lv.prompt_id
            """)
    @UseStringTemplateEngine
    @AllowUnusedBindings
    List<Prompt> findByIds(@Define("ids") @BindList("ids") Set<UUID> ids, @Bind("workspace_id") String workspaceId);

    @SqlQuery("""
             SELECT
                count(id)
             FROM (
                SELECT
                  p.*,
                  (
                    SELECT COUNT(pv.id)
                      FROM prompt_versions pv
                     WHERE pv.workspace_id = p.workspace_id
                     AND pv.prompt_id = p.id
                     AND pv.version_type = 'prompt_version'
                  ) AS version_count
                FROM prompts AS p
                WHERE workspace_id = :workspace_id
                <if(name)> AND name like concat('%', :name, '%') <endif>
                <if(project_id)> AND project_id = :project_id <endif>
            ) AS prompt_full
            <if(filters)> WHERE <filters> <endif>
            """)
    @UseStringTemplateEngine
    @AllowUnusedBindings
    long count(@Define("name") @Bind("name") String name, @Bind("workspace_id") String workspaceId,
            @Define("project_id") @Bind("project_id") UUID projectId,
            @Define("filters") String filters,
            @BindMap Map<String, Object> filterMapping);

    @SqlQuery("SELECT * FROM prompts WHERE name = :name AND workspace_id = :workspace_id" +
            " <if(project_id)> AND project_id = :project_id <endif>")
    @UseStringTemplateEngine
    @AllowUnusedBindings
    Prompt findByName(@Bind("name") String name, @Bind("workspace_id") String workspaceId,
            @Define("project_id") @Bind("project_id") UUID projectId);

    @SqlUpdate("UPDATE prompts SET name = :bean.name, description = :bean.description, last_updated_by = :bean.lastUpdatedBy, "
            +
            " tags = COALESCE(:tags, tags) " +
            " WHERE id = :bean.id AND workspace_id = :workspace_id")
    @UseStringTemplateEngine
    @AllowUnusedBindings
    int update(@Bind("workspace_id") String workspaceId, @BindMethods("bean") Prompt updatedPrompt,
            @Bind("tags") Set<String> tags);

    @SqlUpdate("DELETE FROM prompts WHERE id = :id AND workspace_id = :workspace_id")
    int delete(@Bind("id") UUID id, @Bind("workspace_id") String workspaceId);

    @SqlUpdate("DELETE FROM prompts WHERE id IN (<ids>) AND workspace_id = :workspaceId")
    void delete(@BindList("ids") Set<UUID> ids, @Bind("workspaceId") String workspaceId);

    @SqlUpdate("UPDATE prompts SET last_updated_by = :lastUpdatedBy, last_updated_at = CURRENT_TIMESTAMP(6) WHERE id = :id AND workspace_id = :workspaceId")
    void updateLastUpdatedAt(@Bind("id") UUID id, @Bind("workspaceId") String workspaceId,
            @Bind("lastUpdatedBy") String lastUpdatedBy);

    @SqlQuery("""
            WITH pv_for_commit AS (
                SELECT pv.*
                FROM prompt_versions pv
                WHERE pv.commit = :commit AND pv.workspace_id = :workspace_id AND pv.version_type = 'prompt_version'
            ), active_envs AS (
                SELECT pve.version_id, pve.environment
                FROM prompt_version_envs pve
                INNER JOIN pv_for_commit pvc ON pvc.id = pve.version_id
                WHERE pve.workspace_id = :workspace_id AND pve.ended_at IS NULL
            ), ver_envs AS (
                SELECT version_id, JSON_ARRAYAGG(environment) AS environments
                FROM active_envs
                GROUP BY version_id
            )
            SELECT
                p.*,
                (
                    SELECT COUNT(pv2.id)
                    FROM prompt_versions pv2
                    WHERE pv2.prompt_id = p.id
                    AND pv2.workspace_id = p.workspace_id
                    AND pv2.version_type = 'prompt_version'
                ) AS version_count,
                JSON_OBJECT(
                    'id', pvc.id,
                    'prompt_id', pvc.prompt_id,
                    'commit', pvc.commit,
                    'version_number', pvc.version_number,
                    'template', pvc.template,
                    'metadata', pvc.metadata,
                    'change_description', pvc.change_description,
                    'type', pvc.type,
                    'version_type', pvc.version_type,
                    'environments', ve.environments,
                    'tags', pvc.tags,
                    'created_at', pvc.created_at,
                    'created_by', pvc.created_by,
                    'last_updated_at', pvc.last_updated_at,
                    'last_updated_by', pvc.last_updated_by
                ) AS requested_version
            FROM pv_for_commit pvc
            INNER JOIN prompts p ON pvc.prompt_id = p.id AND p.workspace_id = pvc.workspace_id
            LEFT JOIN ver_envs ve ON ve.version_id = pvc.id
            """)
    List<Prompt> findByCommit(@Bind("commit") String commit, @Bind("workspace_id") String workspaceId);

    @SqlQuery("""
            SELECT
                pv.id AS prompt_version_id,
                pv.commit,
                p.id,
                p.name
            FROM prompt_versions pv
            INNER JOIN prompts p ON pv.prompt_id = p.id
            WHERE pv.commit IN (<commits>)
            AND pv.workspace_id = :workspace_id
            AND pv.version_type = 'prompt_version'
            """)
    @UseStringTemplateEngine
    @AllowUnusedBindings
    @RegisterRowMapper(PromptVersionLinkRowMapper.class)
    List<PromptVersionLink> findPromptsByCommits(
            @Define("commits") @BindList("commits") Collection<String> commits,
            @Bind("workspace_id") String workspaceId);

    /**
     * Per-workspace orphan-prompt counts for the prompt project migration eligibility scan.
     * Returns workspaces with at least one non-demo prompt whose {@code project_id IS NULL},
     * smallest-first so a single cycle can drain low-volume workspaces. Demo prompts are
     * excluded via {@link DemoData#PROMPTS} (utf8mb4_unicode_ci is case-insensitive so a single
     * canonical entry covers all casings). The optional {@code excluded_workspace_ids} bind
     * folds the migration's env-var exclusion list and the persisted trap list into one query.
     */
    @SqlQuery("""
            SELECT
                workspace_id,
                COUNT(*) AS prompts_count
            FROM prompts
            WHERE project_id IS NULL
                AND name NOT IN (<demo_prompt_names>)
                <if(excluded_workspace_ids)> AND workspace_id NOT IN (<excluded_workspace_ids>) <endif>
            GROUP BY workspace_id
            ORDER BY prompts_count ASC
            LIMIT :limit
            """)
    @UseStringTemplateEngine
    @AllowUnusedBindings
    @RegisterConstructorMapper(EligiblePromptWorkspace.class)
    List<EligiblePromptWorkspace> findEligiblePromptWorkspaces(
            @Bind("limit") int limit,
            @Define("demo_prompt_names") @BindList("demo_prompt_names") List<String> demoPromptNames,
            @Define("excluded_workspace_ids") @BindList(value = "excluded_workspace_ids", onEmpty = BindList.EmptyHandling.NULL_VALUE) Set<String> excludedWorkspaceIds);

    /**
     * Returns up to {@code :limit} orphan, non-demo prompt IDs for a single workspace. The cap
     * bounds the per-workspace-per-cycle memory and the size of the ClickHouse {@code IN} list
     * in the downstream classification query. Workspaces with more orphans than the cap are
     * drained over subsequent cycles — the eligibility scan finds them again until none remain.
     */
    @SqlQuery("""
            SELECT id
            FROM prompts
            WHERE workspace_id = :workspace_id
                AND project_id IS NULL
                AND name NOT IN (<demo_prompt_names>)
            LIMIT :limit
            """)
    @UseStringTemplateEngine
    @AllowUnusedBindings
    List<UUID> findOrphanPromptIds(
            @Bind("workspace_id") String workspaceId,
            @Define("demo_prompt_names") @BindList("demo_prompt_names") List<String> demoPromptNames,
            @Bind("limit") int limit);

    /**
     * Idempotent batch assignment. The {@code project_id IS NULL} predicate is the concurrency
     * guard — a concurrent user write that has already set the column to a different value is
     * preserved, and re-runs of the migration are no-ops on already-assigned rows. The schema's
     * {@code ON UPDATE CURRENT_TIMESTAMP(6)} on {@code last_updated_at} stamps the row time.
     */
    @SqlUpdate("""
            UPDATE prompts
            SET project_id = :project_id,
                last_updated_by = :user_name
            WHERE workspace_id = :workspace_id
                AND id IN (<prompt_ids>)
                AND project_id IS NULL
            """)
    @UseStringTemplateEngine
    @AllowUnusedBindings
    int batchSetProjectId(
            @Bind("workspace_id") String workspaceId,
            @Define("prompt_ids") @BindList("prompt_ids") Set<UUID> promptIds,
            @Bind("project_id") UUID projectId,
            @Bind("user_name") String userName);

}

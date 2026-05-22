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
            SELECT
                *,
                (
                    SELECT COUNT(pv.id)
                    FROM prompt_versions pv
                    WHERE pv.prompt_id = p.id
                    AND pv.workspace_id = p.workspace_id
                    AND pv.version_type = 'prompt_version'
                ) AS version_count,
                (
                    SELECT JSON_OBJECT(
                        'id', pv.id,
                        'prompt_id', pv.prompt_id,
                        'commit', pv.commit,
                        'version_number', pv.version_number,
                        'template', pv.template,
                        'metadata', pv.metadata,
                        'change_description', pv.change_description,
                        'type', pv.type,
                        'version_type', pv.version_type,
                        'environment', pv.environment,
                        'tags', pv.tags,
                        'created_at', pv.created_at,
                        'created_by', pv.created_by,
                        'last_updated_at', pv.last_updated_at,
                        'last_updated_by', pv.last_updated_by
                    )
                    FROM prompt_versions pv
                    WHERE pv.prompt_id = p.id
                    AND pv.workspace_id = p.workspace_id
                    AND pv.version_type = 'prompt_version'
                    ORDER BY pv.id DESC
                    LIMIT 1
                ) AS latest_version
                <if(mask_id || environment)>
                ,
                (
                    SELECT JSON_OBJECT(
                        'id', pv.id,
                        'prompt_id', pv.prompt_id,
                        'commit', pv.commit,
                        'version_number', pv.version_number,
                        'template', pv.template,
                        'metadata', pv.metadata,
                        'change_description', pv.change_description,
                        'type', pv.type,
                        'version_type', pv.version_type,
                        'environment', pv.environment,
                        'tags', pv.tags,
                        'created_at', pv.created_at,
                        'created_by', pv.created_by,
                        'last_updated_at', pv.last_updated_at,
                        'last_updated_by', pv.last_updated_by
                    )
                    FROM prompt_versions pv
                    WHERE pv.prompt_id = p.id
                    AND pv.workspace_id = p.workspace_id
                    <if(mask_id)> AND pv.id = :mask_id AND pv.version_type = 'mask' <endif>
                    <if(environment)> AND pv.environment = :environment AND pv.version_type = 'prompt_version' <endif>
                ) AS requested_version
                <endif>
            FROM prompts p
            WHERE id = :id
            AND workspace_id = :workspace_id
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
            ), latest_versions AS (
            	SELECT
              JSON_OBJECT(
                'id', id,
                'prompt_id', prompt_id,
                'commit', commit,
                'version_number', version_number,
                'template', template,
                'metadata', metadata,
                'change_description', change_description,
                'type', type,
                'version_type', version_type,
                'environment', environment,
                'tags', tags,
                'created_at', created_at,
                'created_by', created_by,
                'last_updated_at', last_updated_at,
                'last_updated_by', last_updated_by
              ) AS latest_version,
              prompt_id
              FROM (
                SELECT
                  pv.*,
                  ROW_NUMBER() OVER (
                    PARTITION BY pv.prompt_id
                    ORDER BY pv.id DESC
                  ) AS rn
                FROM prompt_versions pv
                WHERE pv.workspace_id = :workspace_id
                  AND pv.version_type = 'prompt_version'
                  <if(ids)> AND pv.prompt_id IN (<ids>) <endif>
              ) ranked
              WHERE ranked.rn = 1
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
                    'id', pv.id,
                    'prompt_id', pv.prompt_id,
                    'commit', pv.commit,
                    'version_number', pv.version_number,
                    'template', pv.template,
                    'metadata', pv.metadata,
                    'change_description', pv.change_description,
                    'type', pv.type,
                    'version_type', pv.version_type,
                    'environment', pv.environment,
                    'tags', pv.tags,
                    'created_at', pv.created_at,
                    'created_by', pv.created_by,
                    'last_updated_at', pv.last_updated_at,
                    'last_updated_by', pv.last_updated_by
                ) AS requested_version
            FROM prompt_versions pv
            INNER JOIN prompts p ON pv.prompt_id = p.id AND p.workspace_id = pv.workspace_id
            WHERE pv.commit = :commit
            AND pv.workspace_id = :workspace_id
            AND pv.version_type = 'prompt_version'
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

package com.comet.opik.domain.evaluators;

import com.comet.opik.api.evaluators.AutomationRule;
import com.comet.opik.infrastructure.db.UUIDArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterRowMapper;
import org.jdbi.v3.sqlobject.customizer.AllowUnusedBindings;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindList;
import org.jdbi.v3.sqlobject.customizer.BindMethods;
import org.jdbi.v3.sqlobject.customizer.Define;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;
import org.jdbi.v3.stringtemplate4.UseStringTemplateEngine;

import java.util.Set;
import java.util.UUID;

@RegisterArgumentFactory(UUIDArgumentFactory.class)
@RegisterRowMapper(AutomationRuleRowMapper.class)
public interface AutomationRuleDAO {

    /**
     * Automation rules have always been project-scoped (project_id NOT NULL since migration 000009).
     * Unlike other entities, the V1 signal here is rules assigned to multiple projects via the
     * junction table — V2's single-project UI can't represent them.
     */
    @SqlQuery("""
            SELECT EXISTS(
                SELECT 1 FROM automation_rule_projects
                WHERE workspace_id = :workspaceId
                GROUP BY rule_id
                HAVING COUNT(project_id) > 1
            )""")
    boolean hasVersion1AutomationRules(@Bind("workspaceId") String workspaceId);

    @SqlUpdate("INSERT INTO automation_rules(id, workspace_id, `action`, name, sampling_rate, enabled, filters) "
            +
            "VALUES (:rule.id, :workspaceId, :rule.action, :rule.name, :rule.samplingRate, :rule.enabled, :rule.filters)")
    void saveBaseRule(@BindMethods("rule") AutomationRuleModel rule, @Bind("workspaceId") String workspaceId);

    /**
     * Returns all rule names within the given projects, used to auto-suffix colliding names on create
     * (OPIK-7371). Project-scoped through the junction table AND the legacy {@code project_id} column, so
     * pre-junction rules (created before migration 000041 and never lazy-migrated) are also considered -
     * these still exist on older SaaS/enterprise installs. Collision matching is done in Java
     * (case-insensitive), so no name filter is applied here - this avoids LIKE metacharacter/escape
     * pitfalls and keeps comparison consistent with the DB collation. Rules per project are few, so
     * fetching the full set is cheap.
     */
    @SqlQuery("""
            SELECT DISTINCT rule.name
            FROM automation_rules rule
            LEFT JOIN automation_rule_projects arp ON rule.id = arp.rule_id
            WHERE rule.workspace_id = :workspaceId
            AND (arp.project_id IN (<projectIds>) OR rule.project_id IN (<projectIds>))
            """)
    @UseStringTemplateEngine
    @AllowUnusedBindings
    Set<String> findNamesByProjects(
            @Define("projectIds") @BindList(onEmpty = BindList.EmptyHandling.NULL_VALUE, value = "projectIds") Set<UUID> projectIds,
            @Bind("workspaceId") String workspaceId);

    /**
     * Same as {@link #findNamesByProjects} but excludes a single rule by id, used on update so a rule's own
     * name (unchanged name or a non-name edit) does not count as a self-collision (OPIK-7371).
     */
    @SqlQuery("""
            SELECT DISTINCT rule.name
            FROM automation_rules rule
            LEFT JOIN automation_rule_projects arp ON rule.id = arp.rule_id
            WHERE rule.workspace_id = :workspaceId
            AND rule.id != :excludeRuleId
            AND (arp.project_id IN (<projectIds>) OR rule.project_id IN (<projectIds>))
            """)
    @UseStringTemplateEngine
    @AllowUnusedBindings
    Set<String> findNamesByProjectsExcludingRule(
            @Define("projectIds") @BindList(onEmpty = BindList.EmptyHandling.NULL_VALUE, value = "projectIds") Set<UUID> projectIds,
            @Bind("workspaceId") String workspaceId,
            @Bind("excludeRuleId") UUID excludeRuleId);

    @SqlUpdate("""
            UPDATE automation_rules
            SET name = :name,
                sampling_rate = :samplingRate,
                enabled = :enabled,
                filters = :filters
            WHERE id = :id AND workspace_id = :workspaceId
            """)
    int updateBaseRule(@Bind("id") UUID id,
            @Bind("workspaceId") String workspaceId,
            @Bind("name") String name,
            @Bind("samplingRate") float samplingRate,
            @Bind("enabled") boolean enabled,
            @Bind("filters") String filters);

    /**
     * Clears the legacy project_id field to prevent stale data.
     * Should be called when projects are removed from the junction table.
     */
    @SqlUpdate("UPDATE automation_rules SET project_id = NULL WHERE id = :id AND workspace_id = :workspaceId")
    int clearLegacyProjectId(@Bind("id") UUID id, @Bind("workspaceId") String workspaceId);

    @SqlUpdate("""
            DELETE FROM automation_rules
            WHERE workspace_id = :workspaceId
            <if(ids)> AND id IN (<ids>) <endif>
            """)
    @UseStringTemplateEngine
    @AllowUnusedBindings
    void deleteBaseRules(
            @Define("ids") @BindList(onEmpty = BindList.EmptyHandling.NULL_VALUE, value = "ids") Set<UUID> ids,
            @Bind("workspaceId") String workspaceId);

    @SqlQuery("""
            SELECT COUNT(DISTINCT rule.id)
            FROM automation_rules rule
            <if(projectIds)>
            LEFT JOIN automation_rule_projects arp ON rule.id = arp.rule_id
            <endif>
            WHERE rule.workspace_id = :workspaceId
            <if(projectIds)> AND arp.project_id IN (<projectIds>) <endif>
            <if(action)> AND rule.`action` = :action <endif>
            """)
    @UseStringTemplateEngine
    @AllowUnusedBindings
    long findCount(
            @Define("projectIds") @BindList(onEmpty = BindList.EmptyHandling.NULL_VALUE, value = "projectIds") Set<UUID> projectIds,
            @Bind("workspaceId") String workspaceId,
            @Define("action") @Bind("action") AutomationRule.AutomationRuleAction action);
}

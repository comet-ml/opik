package com.comet.opik.domain.evaluators;

import com.comet.opik.api.evaluators.AutomationRule;
import com.comet.opik.api.evaluators.EvalTriggerScope;
import com.comet.opik.infrastructure.db.EvalTriggerScopeColumnMapper;
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

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@RegisterArgumentFactory(UUIDArgumentFactory.class)
@RegisterArgumentFactory(EvalTriggerScopeColumnMapper.class)
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

    @SqlUpdate("INSERT INTO automation_rules(id, workspace_id, `action`, name, sampling_rate, enabled, trigger_scope, filters) "
            +
            "VALUES (:rule.id, :workspaceId, :rule.action, :rule.name, :rule.samplingRate, :rule.enabled, :rule.triggerScope, :rule.filters)")
    void saveBaseRule(@BindMethods("rule") AutomationRuleModel rule, @Bind("workspaceId") String workspaceId);

    /**
     * Returns existing rule names in the given project(s) that start with {@code namePrefix}, used to
     * auto-suffix colliding names (OPIK-7371). Scoped per project via the junction table (the authoritative
     * association after the AutomationRuleProjectMigration backfill); the legacy {@code project_id} column
     * is intentionally not used (it is nulled on update). {@code excludeRuleId} (optional) skips a single
     * rule so its own name is not treated as a self-collision on update. The {@code LIKE} prefix keeps the
     * result set small and index-backed - callers MUST pass a prefix escaped via
     * {@link AutomationRuleNames#likePrefix(String)} so LIKE metacharacters in the name are matched
     * literally. Final precise matching is done in Java over this small candidate set.
     * <p>
     * Assumptions (optimistic, per OPIK-7371): the junction backfill is complete, so a rare un-backfilled
     * legacy rule (no junction row) may be missed - degrading to a duplicate name, not an error; and
     * concurrent creates of the same name race without a DB constraint.
     */
    @SqlQuery("""
            SELECT DISTINCT rule.name
            FROM automation_rules rule
            JOIN automation_rule_projects arp ON rule.id = arp.rule_id
            WHERE rule.workspace_id = :workspaceId
            AND arp.project_id IN (<projectIds>)
            AND rule.name LIKE concat(:namePrefix, '%')
            <if(excludeRuleId)> AND rule.id != :excludeRuleId <endif>
            """)
    @UseStringTemplateEngine
    @AllowUnusedBindings
    Set<String> findCollidingNames(
            @Define("projectIds") @BindList(onEmpty = BindList.EmptyHandling.NULL_VALUE, value = "projectIds") Set<UUID> projectIds,
            @Bind("workspaceId") String workspaceId,
            @Bind("namePrefix") String namePrefix,
            @Define("excludeRuleId") @Bind("excludeRuleId") UUID excludeRuleId);

    /**
     * Returns the currently stored name of a rule, or {@code null} if it does not exist. Used on update to
     * skip name resolution entirely for non-name edits (OPIK-7371).
     */
    @SqlQuery("SELECT name FROM automation_rules WHERE id = :id AND workspace_id = :workspaceId")
    Optional<String> findNameById(@Bind("id") UUID id, @Bind("workspaceId") String workspaceId);

    @SqlUpdate("""
            UPDATE automation_rules
            SET name = :name,
                sampling_rate = :samplingRate,
                enabled = :enabled,
                trigger_scope = :triggerScope,
                filters = :filters
            WHERE id = :id AND workspace_id = :workspaceId
            """)
    int updateBaseRule(@Bind("id") UUID id,
            @Bind("workspaceId") String workspaceId,
            @Bind("name") String name,
            @Bind("samplingRate") float samplingRate,
            @Bind("enabled") boolean enabled,
            @Bind("triggerScope") EvalTriggerScope triggerScope,
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

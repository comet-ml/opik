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

    @SqlUpdate("INSERT INTO automation_rules(id, workspace_id, `action`, name, sampling_rate, enabled, trigger_scope, filters) "
            +
            "VALUES (:rule.id, :workspaceId, :rule.action, :rule.name, :rule.samplingRate, :rule.enabled, :rule.triggerScope, :rule.filters)")
    void saveBaseRule(@BindMethods("rule") AutomationRuleModel rule, @Bind("workspaceId") String workspaceId);

    /**
     * Returns existing rule names in the given project(s) that start with {@code namePrefix}, used to
     * auto-suffix colliding names (OPIK-7371). Scoped per project via the junction table (the authoritative
     * association after the AutomationRuleProjectMigration backfill); the legacy {@code project_id} column
     * is intentionally not used (it is nulled on update). {@code excludeRuleId} (optional) skips a single
     * rule so its own name is not treated as a self-collision on update. Callers MUST pass a prefix escaped
     * via {@link AutomationRuleNames#likePrefix(String)} so LIKE metacharacters in the name are matched
     * literally. Final precise matching is done in Java over the returned candidate set.
     * <p>
     * What actually bounds this query is the <em>project</em> filter, not the name prefix. Measured on
     * MySQL 8.4 with 50k rules in one workspace: for a typical project (~100 rules) the optimizer drives
     * from {@code automation_rule_projects} on {@code project_id} and reaches {@code automation_rules} by
     * primary key, so the name is only a residual filter. The {@code (workspace_id, name)} index added in
     * migration 000092 is <em>not</em> selected in either that case or the skewed one (a single project
     * holding 20k rules, where the optimizer scans ~25k rows via {@code automation_rules_idx} instead).
     * Forcing the index does produce a better plan (covering range scan, half the rows), so the index is
     * usable but currently inert - see the OPIK-7371 review thread before relying on it.
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
            AND rule.name LIKE concat(:namePrefix, '%') ESCAPE '!'
            <if(excludeRuleId)> AND rule.id != :excludeRuleId <endif>
            """)
    @UseStringTemplateEngine
    @AllowUnusedBindings
    Set<String> findCandidateNames(
            @Define("projectIds") @BindList(onEmpty = BindList.EmptyHandling.NULL_VALUE, value = "projectIds") Set<UUID> projectIds,
            @Bind("workspaceId") String workspaceId,
            @Bind("namePrefix") String namePrefix,
            @Define("excludeRuleId") @Bind("excludeRuleId") UUID excludeRuleId);

    /**
     * Returns the currently stored name of a rule, or empty if it does not exist. Used on update to skip
     * name resolution entirely for non-name edits (OPIK-7371).
     * <p>
     * Deliberately a single-column projection rather than the full rule payload: the registered
     * {@link AutomationRuleRowMapper} dispatches on {@code action} to {@link AutomationRuleEvaluatorModel},
     * so returning a whole rule would require joining {@code automation_rule_evaluators} and deserializing
     * the {@code code} payload (the full LLM-as-judge prompt) on every update just to read this one column.
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

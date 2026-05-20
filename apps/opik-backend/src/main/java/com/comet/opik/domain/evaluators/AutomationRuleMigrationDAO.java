package com.comet.opik.domain.evaluators;

import com.comet.opik.infrastructure.db.UUIDArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterRowMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindList;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;
import org.jdbi.v3.stringtemplate4.UseStringTemplateEngine;

import java.util.List;
import java.util.Set;
import java.util.UUID;

@RegisterArgumentFactory(UUIDArgumentFactory.class)
public interface AutomationRuleMigrationDAO {

    record EligibleWorkspace(String workspaceId, long multiProjectRuleCount) {
    }

    @SqlQuery("""
            SELECT workspace_id, COUNT(*) AS multi_project_rule_count
            FROM (
                SELECT arp.workspace_id, arp.rule_id
                FROM automation_rule_projects arp
                <if(demoRuleNames)>
                JOIN automation_rules ar ON arp.rule_id = ar.id AND arp.workspace_id = ar.workspace_id
                <endif>
                WHERE arp.workspace_id NOT IN (<excludedWorkspaceIds>)
                <if(demoRuleNames)>
                  AND ar.name NOT IN (<demoRuleNames>)
                <endif>
                GROUP BY arp.workspace_id, arp.rule_id
                HAVING COUNT(arp.project_id) > 1
            ) multi_project_rules
            GROUP BY workspace_id
            ORDER BY multi_project_rule_count ASC
            LIMIT :limit
            """)
    @UseStringTemplateEngine
    @RegisterRowMapper(EligibleWorkspaceRowMapper.class)
    List<EligibleWorkspace> findEligibleWorkspaces(
            @BindList("excludedWorkspaceIds") Set<String> excludedWorkspaceIds,
            @BindList(onEmpty = BindList.EmptyHandling.NULL_VALUE, value = "demoRuleNames") List<String> demoRuleNames,
            @Bind("limit") int limit);

    @SqlQuery("""
            SELECT DISTINCT arp.rule_id
            FROM automation_rule_projects arp
            WHERE arp.workspace_id = :workspaceId
            <if(demoRuleNames)>
              AND arp.rule_id NOT IN (
                SELECT ar.id FROM automation_rules ar
                WHERE ar.name IN (<demoRuleNames>) AND ar.workspace_id = :workspaceId
              )
            <endif>
            GROUP BY arp.rule_id
            HAVING COUNT(arp.project_id) > 1
            """)
    @UseStringTemplateEngine
    List<UUID> findMultiProjectRuleIds(
            @Bind("workspaceId") String workspaceId,
            @BindList(onEmpty = BindList.EmptyHandling.NULL_VALUE, value = "demoRuleNames") List<String> demoRuleNames);

    @SqlQuery("""
            SELECT rule.id, rule.project_id AS legacy_project_id,
                   rule.action, rule.name AS name, rule.sampling_rate, rule.enabled, rule.filters,
                   evaluator.type, evaluator.code,
                   evaluator.created_at, evaluator.created_by, evaluator.last_updated_at, evaluator.last_updated_by
            FROM automation_rules rule
            JOIN automation_rule_evaluators evaluator ON rule.id = evaluator.id
            WHERE rule.id = :ruleId AND rule.workspace_id = :workspaceId
            """)
    @RegisterRowMapper(AutomationRuleEvaluatorRowMapper.class)
    AutomationRuleEvaluatorModel<?> findRuleWithEvaluator(
            @Bind("ruleId") UUID ruleId,
            @Bind("workspaceId") String workspaceId);

    @SqlUpdate("DELETE FROM automation_rule_projects WHERE rule_id = :ruleId AND workspace_id = :workspaceId")
    int deleteJunctionByRuleId(@Bind("ruleId") UUID ruleId, @Bind("workspaceId") String workspaceId);

    @SqlUpdate("""
            INSERT INTO automation_rule_projects (rule_id, project_id, workspace_id)
            VALUES (:ruleId, :projectId, :workspaceId)
            """)
    void insertJunction(@Bind("ruleId") UUID ruleId,
            @Bind("projectId") UUID projectId,
            @Bind("workspaceId") String workspaceId);

    @SqlUpdate("""
            INSERT INTO automation_rules (id, workspace_id, `action`, name, sampling_rate, enabled, filters)
            SELECT :newId, workspace_id, `action`, name, sampling_rate, enabled, filters
            FROM automation_rules
            WHERE id = :sourceId AND workspace_id = :workspaceId
            """)
    void copyBaseRule(@Bind("newId") UUID newId,
            @Bind("sourceId") UUID sourceId,
            @Bind("workspaceId") String workspaceId);

    @SqlUpdate("""
            INSERT INTO automation_rule_evaluators (id, `type`, code, created_by, last_updated_by)
            SELECT :newId, `type`, code, created_by, last_updated_by
            FROM automation_rule_evaluators
            WHERE id = :sourceId
            """)
    void copyEvaluator(@Bind("newId") UUID newId,
            @Bind("sourceId") UUID sourceId);

    @SqlUpdate("UPDATE automation_rules SET project_id = NULL WHERE id = :id AND workspace_id = :workspaceId")
    int clearLegacyProjectId(@Bind("id") UUID id, @Bind("workspaceId") String workspaceId);
}

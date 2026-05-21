package com.comet.opik.domain.evaluators;

import com.comet.opik.infrastructure.db.UUIDArgumentFactory;
import lombok.Builder;
import lombok.NonNull;
import org.jdbi.v3.sqlobject.config.RegisterArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.AllowUnusedBindings;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindList;
import org.jdbi.v3.sqlobject.customizer.Define;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;
import org.jdbi.v3.stringtemplate4.UseStringTemplateEngine;

import java.util.List;
import java.util.Set;
import java.util.UUID;

@RegisterArgumentFactory(UUIDArgumentFactory.class)
public interface AutomationRuleMigrationDAO {

    @Builder(toBuilder = true)
    record EligibleWorkspace(@NonNull String workspaceId, long multiProjectRuleCount) {
    }

    @SqlQuery("""
            SELECT workspace_id, COUNT(*) AS multi_project_rule_count
            FROM (
                SELECT arp.workspace_id, arp.rule_id
                FROM automation_rule_projects arp
                <if(demoRuleNames)>
                JOIN automation_rules ar ON arp.rule_id = ar.id AND arp.workspace_id = ar.workspace_id
                <endif>
                WHERE 1=1
                <if(excludedWorkspaceIds)>
                  AND arp.workspace_id NOT IN (<excludedWorkspaceIds>)
                <endif>
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
    @AllowUnusedBindings
    @RegisterConstructorMapper(EligibleWorkspace.class)
    List<EligibleWorkspace> findEligibleWorkspaces(
            @Define("excludedWorkspaceIds") @BindList(onEmpty = BindList.EmptyHandling.NULL_VALUE, value = "excludedWorkspaceIds") Set<String> excludedWorkspaceIds,
            @BindList(onEmpty = BindList.EmptyHandling.NULL_VALUE, value = "demoRuleNames") List<String> demoRuleNames,
            @Bind("limit") int limit);

    @SqlQuery("""
            SELECT arp.rule_id
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
            LIMIT :limit
            """)
    @UseStringTemplateEngine
    List<UUID> findMultiProjectRuleIds(
            @Bind("workspaceId") String workspaceId,
            @BindList(onEmpty = BindList.EmptyHandling.NULL_VALUE, value = "demoRuleNames") List<String> demoRuleNames,
            @Bind("limit") int limit);

    @SqlUpdate("DELETE FROM automation_rule_projects WHERE rule_id = :ruleId AND workspace_id = :workspaceId")
    int deleteJunctionByRuleId(@Bind("ruleId") UUID ruleId, @Bind("workspaceId") String workspaceId);

    @SqlUpdate("""
            INSERT INTO automation_rule_projects (rule_id, project_id, workspace_id)
            VALUES (:ruleId, :projectId, :workspaceId)
            """)
    void insertJunction(@Bind("ruleId") UUID ruleId,
            @Bind("projectId") UUID projectId,
            @Bind("workspaceId") String workspaceId);

    @SqlUpdate("UPDATE automation_rules SET enabled = false WHERE id = :ruleId AND workspace_id = :workspaceId")
    int disableRule(@Bind("ruleId") UUID ruleId, @Bind("workspaceId") String workspaceId);

    // Fields are enumerated intentionally — project_id is excluded so the cloned rule
    // does not inherit the legacy column value from the source row.
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
}

package com.comet.opik.domain.evaluators;

import com.comet.opik.infrastructure.db.UUIDArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterArgumentFactory;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindList;
import org.jdbi.v3.sqlobject.customizer.Define;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;
import org.jdbi.v3.stringtemplate4.UseStringTemplateEngine;

import java.util.Set;
import java.util.UUID;

@RegisterArgumentFactory(UUIDArgumentFactory.class)
interface AutomationRuleProjectsDAO {

    @SqlUpdate("INSERT INTO automation_rule_projects(rule_id, project_id, workspace_id) " +
            "VALUES (:ruleId, :projectId, :workspaceId)")
    void saveRuleProject(@Bind("ruleId") UUID ruleId,
            @Bind("projectId") UUID projectId,
            @Bind("workspaceId") String workspaceId);

    default void saveRuleProjects(UUID ruleId, Set<UUID> projectIds, String workspaceId) {
        for (UUID projectId : projectIds) {
            saveRuleProject(ruleId, projectId, workspaceId);
        }
    }

    @SqlQuery("SELECT project_id FROM automation_rule_projects " +
            "WHERE rule_id = :ruleId AND workspace_id = :workspaceId")
    Set<UUID> findProjectIdsByRuleId(@Bind("ruleId") UUID ruleId,
            @Bind("workspaceId") String workspaceId);

    @SqlUpdate("DELETE FROM automation_rule_projects " +
            "WHERE rule_id = :ruleId AND workspace_id = :workspaceId")
    int deleteByRuleId(@Bind("ruleId") UUID ruleId,
            @Bind("workspaceId") String workspaceId);

    @SqlUpdate("""
            DELETE FROM automation_rule_projects
            WHERE workspace_id = :workspaceId
            <if(ruleIds)> AND rule_id IN (<ruleIds>) <endif>
            """)
    @UseStringTemplateEngine
    int deleteByRuleIds(
            @Define("ruleIds") @BindList(onEmpty = BindList.EmptyHandling.NULL_VALUE, value = "ruleIds") Set<UUID> ruleIds,
            @Bind("workspaceId") String workspaceId);
}

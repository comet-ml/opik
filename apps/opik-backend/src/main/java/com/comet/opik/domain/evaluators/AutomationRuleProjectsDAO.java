package com.comet.opik.domain.evaluators;

import com.comet.opik.infrastructure.db.UUIDArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterArgumentFactory;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindList;
import org.jdbi.v3.sqlobject.customizer.Define;
import org.jdbi.v3.sqlobject.statement.SqlBatch;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;
import org.jdbi.v3.stringtemplate4.UseStringTemplateEngine;

import java.util.Set;
import java.util.UUID;

@RegisterArgumentFactory(UUIDArgumentFactory.class)
interface AutomationRuleProjectsDAO {

    @SqlBatch("INSERT INTO automation_rule_projects(rule_id, project_id, workspace_id) " +
            "VALUES (:ruleId, :projectId, :workspaceId)")
    void saveRuleProjects(@Bind("ruleId") UUID ruleId,
            @Bind("projectId") Set<UUID> projectIds,
            @Bind("workspaceId") String workspaceId);

    @SqlQuery("SELECT project_id FROM automation_rule_projects " +
            "WHERE rule_id = :ruleId AND workspace_id = :workspaceId")
    Set<UUID> findProjectIdsByRuleId(@Bind("ruleId") UUID ruleId,
            @Bind("workspaceId") String workspaceId);

    @SqlUpdate("DELETE FROM automation_rule_projects " +
            "WHERE rule_id = :ruleId AND workspace_id = :workspaceId")
    void deleteByRuleId(@Bind("ruleId") UUID ruleId,
            @Bind("workspaceId") String workspaceId);

    @SqlUpdate("""
            DELETE FROM automation_rule_projects
            WHERE workspace_id = :workspaceId
            <if(ruleIds)> AND rule_id IN (<ruleIds>) <endif>
            """)
    @UseStringTemplateEngine
    void deleteByRuleIds(
            @Define("ruleIds") @BindList(onEmpty = BindList.EmptyHandling.NULL_VALUE, value = "ruleIds") Set<UUID> ruleIds,
            @Bind("workspaceId") String workspaceId);

    @SqlUpdate("""
            DELETE FROM automation_rule_projects
            WHERE rule_id = :ruleId
            AND workspace_id = :workspaceId
            AND project_id NOT IN (<projectIds>)
            """)
    void deleteRemovedProjects(@Bind("ruleId") UUID ruleId,
            @BindList("projectIds") Set<UUID> projectIds,
            @Bind("workspaceId") String workspaceId);

    @SqlBatch("INSERT IGNORE INTO automation_rule_projects(rule_id, project_id, workspace_id) " +
            "VALUES (:ruleId, :projectId, :workspaceId)")
    void addNewProjects(@Bind("ruleId") UUID ruleId,
            @Bind("projectId") Set<UUID> projectIds,
            @Bind("workspaceId") String workspaceId);
}

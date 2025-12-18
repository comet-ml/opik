package com.comet.opik.domain.evaluators;

import com.comet.opik.infrastructure.db.UUIDArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterArgumentFactory;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindList;
import org.jdbi.v3.sqlobject.customizer.BindMethods;
import org.jdbi.v3.sqlobject.customizer.Define;
import org.jdbi.v3.sqlobject.statement.SqlBatch;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;
import org.jdbi.v3.stringtemplate4.UseStringTemplateEngine;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@RegisterArgumentFactory(UUIDArgumentFactory.class)
interface AutomationRuleProjectsDAO {

    @SqlBatch("INSERT INTO automation_rule_projects(rule_id, project_id, workspace_id) " +
            "VALUES (:bean.ruleId, :bean.projectId, :bean.workspaceId)")
    void batchInsertRuleProjects(@BindMethods("bean") List<RuleProject> ruleProjects);

    default void saveRuleProjects(UUID ruleId, Set<UUID> projectIds, String workspaceId) {
        if (projectIds.isEmpty()) {
            return;
        }
        var ruleProjects = projectIds.stream()
                .map(projectId -> new RuleProject(ruleId, projectId, workspaceId))
                .collect(Collectors.toList());
        batchInsertRuleProjects(ruleProjects);
    }

    record RuleProject(UUID ruleId, UUID projectId, String workspaceId) {
    }

    @SqlQuery("SELECT project_id FROM automation_rule_projects " +
            "WHERE rule_id = :ruleId AND workspace_id = :workspaceId")
    Set<UUID> findProjectIdsByRuleId(@Bind("ruleId") UUID ruleId,
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

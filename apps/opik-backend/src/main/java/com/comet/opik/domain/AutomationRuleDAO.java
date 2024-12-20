package com.comet.opik.domain;

import com.comet.opik.api.AutomationRule;
import com.comet.opik.api.AutomationRuleEvaluator;
import com.comet.opik.infrastructure.db.UUIDArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.config.RegisterRowMapper;
import org.jdbi.v3.sqlobject.customizer.AllowUnusedBindings;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindList;
import org.jdbi.v3.sqlobject.customizer.BindMethods;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

import java.util.Set;
import java.util.UUID;

@RegisterArgumentFactory(UUIDArgumentFactory.class)
@RegisterRowMapper(AutomationRuleRowMapper.class)
@RegisterConstructorMapper(AutomationRuleEvaluator.class)
interface AutomationRuleDAO {

    @SqlUpdate("INSERT INTO automation_rules(id, project_id, workspace_id, `action`, created_by, last_updated_by) "+
               "VALUES (:rule.id, :rule.projectId, :workspaceId, :rule.action, :rule.createdBy, :rule.lastUpdatedBy)")
    void saveRule(@BindMethods("rule") AutomationRule rule, @Bind("workspaceId") String workspaceId);

    @SqlUpdate("""
            UPDATE automation_rules
            SET last_updated_by = :lastUpdatedBy
            WHERE id = :id AND project_id = :projectId AND workspace_id = :workspaceId
            """)
    int updateRule(@Bind("id") UUID id,
                   @Bind("projectId") UUID projectId,
                   @Bind("workspaceId") String workspaceId,
                   @Bind("lastUpdatedBy") String lastUpdatedBy);

    @SqlUpdate("DELETE FROM automation_rules WHERE id = :id AND project_id = :projectId AND workspace_id = :workspaceId")
    void delete(@Bind("id") UUID id, @Bind("projectId") UUID projectId, @Bind("workspaceId") String workspaceId);

    @SqlUpdate("DELETE FROM automation_rules WHERE project_id = :projectId AND workspace_id = :workspaceId")
    void deleteByProject(@Bind("projectId") UUID projectId, @Bind("workspaceId") String workspaceId);

    @SqlUpdate("DELETE FROM automation_rules WHERE id IN (<ids>) AND project_id = :projectId AND workspace_id = :workspaceId")
    void delete(@BindList("ids") Set<UUID> ids, @Bind("projectId") UUID projectId, @Bind("workspaceId") String workspaceId);

    @SqlQuery("SELECT COUNT(*) FROM automation_rules WHERE project_id = :projectId AND workspace_id = :workspaceId")
    long findRuleCount(@Bind("projectId") UUID projectId, @Bind("workspaceId") String workspaceId);
}

package com.comet.opik.domain;

import com.comet.opik.api.AutomationRuleEvaluator;
import com.comet.opik.api.AutomationRuleEvaluatorUpdate;
import com.comet.opik.infrastructure.db.UUIDArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.AllowUnusedBindings;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindList;
import org.jdbi.v3.sqlobject.customizer.BindMethods;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;
import org.jdbi.v3.stringtemplate4.UseStringTemplateEngine;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@RegisterArgumentFactory(UUIDArgumentFactory.class)
@RegisterConstructorMapper(AutomationRuleEvaluator.class)
public interface AutomationRuleDAO {

    @SqlUpdate("INSERT INTO automation_rule_evaluators(id, project_id, workspace_id, evaluator_type, sampling_rate, code, created_by, last_updated_by) "+
               "VALUES (:rule.id, :rule.projectId, :workspaceId, :rule.evaluatorType, :rule.samplingRate, :rule.code, :rule.createdBy, :rule.lastUpdatedBy)")
    void save(@BindMethods("rule") AutomationRuleEvaluator rule, @Bind("workspaceId") String workspaceId);

    @SqlUpdate("""
            UPDATE automation_rule_evaluators
            SET
                sampling_rate = :rule.samplingRate,
                code = :rule.code,
                last_updated_by = :lastUpdatedBy
            WHERE id = :id AND project_id = :projectId AND workspace_id = :workspaceId
            """)
    int update(@Bind("id") UUID id,
            @Bind("projectId") UUID projectId,
            @Bind("workspaceId") String workspaceId,
            @BindMethods("rule") AutomationRuleEvaluatorUpdate ruleUpdate,
            @Bind("lastUpdatedBy") String lastUpdatedBy);

    @SqlQuery("SELECT * FROM automation_rule_evaluators WHERE id = :id AND project_id = :projectId AND workspace_id = :workspaceId")
    Optional<AutomationRuleEvaluator> findById(@Bind("id") UUID id, @Bind("projectId") UUID projectId,
            @Bind("workspaceId") String workspaceId);

    @SqlQuery("SELECT * FROM automation_rule_evaluators WHERE id IN (<ids>) AND project_id = :projectId AND workspace_id = :workspaceId")
    List<AutomationRuleEvaluator> findByIds(@BindList("ids") Set<UUID> ids, @Bind("projectId") UUID projectId,
            @Bind("workspaceId") String workspaceId);

    @SqlQuery("SELECT * FROM automation_rule_evaluators WHERE project_id = :projectId AND workspace_id = :workspaceId")
    List<AutomationRuleEvaluator> findByProjectId(@Bind("projectId") UUID projectId, @Bind("workspaceId") String workspaceId);

    @SqlUpdate("DELETE FROM automation_rule_evaluators WHERE id = :id AND project_id = :projectId AND workspace_id = :workspaceId")
    void delete(@Bind("id") UUID id, @Bind("projectId") UUID projectId, @Bind("workspaceId") String workspaceId);

    @SqlUpdate("DELETE FROM automation_rule_evaluators WHERE project_id = :projectId AND workspace_id = :workspaceId")
    void deleteByProject(@Bind("projectId") UUID projectId, @Bind("workspaceId") String workspaceId);

    @SqlUpdate("DELETE FROM automation_rule_evaluators WHERE id IN (<ids>) AND project_id = :projectId AND workspace_id = :workspaceId")
    void delete(@BindList("ids") Set<UUID> ids, @Bind("projectId") UUID projectId, @Bind("workspaceId") String workspaceId);

    @SqlQuery("SELECT * FROM automation_rule_evaluators " +
            " WHERE project_id = :projectId AND workspace_id = :workspaceId " +
            " LIMIT :limit OFFSET :offset ")
    @UseStringTemplateEngine
    @AllowUnusedBindings
    List<AutomationRuleEvaluator> find(@Bind("limit") int limit,
            @Bind("offset") int offset,
            @Bind("projectId") UUID projectId,
            @Bind("workspaceId") String workspaceId);

    @SqlQuery("SELECT COUNT(*) FROM automation_rule_evaluators WHERE project_id = :projectId AND workspace_id = :workspaceId")
    long findCount(@Bind("projectId") UUID projectId, @Bind("workspaceId") String workspaceId);

    @SqlQuery("SELECT id FROM automation_rule_evaluators WHERE id IN (<ids>) and project_id = :projectId AND workspace_id = :workspaceId")
    Set<UUID> exists(@BindList("ids") Set<UUID> ruleIds, @Bind("projectId") UUID projectId,
            @Bind("workspaceId") String workspaceId);
}

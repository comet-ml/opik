package com.comet.opik.domain;

import com.comet.opik.api.AutomationRule;
import com.comet.opik.api.AutomationRuleEvaluatorCriteria;
import com.comet.opik.api.AutomationRuleEvaluatorType;
import com.comet.opik.infrastructure.db.JsonNodeArgumentFactory;
import com.comet.opik.infrastructure.db.UUIDArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.config.RegisterRowMapper;
import org.jdbi.v3.sqlobject.customizer.AllowUnusedBindings;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindList;
import org.jdbi.v3.sqlobject.customizer.BindMethods;
import org.jdbi.v3.sqlobject.customizer.Define;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;
import org.jdbi.v3.stringtemplate4.UseStringTemplateEngine;

import java.util.List;
import java.util.Set;
import java.util.UUID;

@RegisterArgumentFactory(UUIDArgumentFactory.class)
@RegisterArgumentFactory(JsonNodeArgumentFactory.class)
@RegisterConstructorMapper(LlmAsJudgeAutomationRuleEvaluatorModel.class)
@RegisterConstructorMapper(UserDefinedMetricPythonAutomationRuleEvaluatorModel.class)
@RegisterRowMapper(AutomationRuleEvaluatorRowMapper.class)
public interface AutomationRuleEvaluatorDAO extends AutomationRuleDAO {

    @SqlUpdate("INSERT INTO automation_rule_evaluators(id, `type`, code, created_by, last_updated_by) " +
            "VALUES (:rule.id, :rule.type, :rule.code, :rule.createdBy, :rule.lastUpdatedBy)")
    <T> void saveEvaluator(@BindMethods("rule") AutomationRuleEvaluatorModel<T> rule);

    @SqlUpdate("""
            UPDATE automation_rule_evaluators
            SET code = :rule.code,
                last_updated_by = :rule.lastUpdatedBy
            WHERE id = :id
            """)
    <T> int updateEvaluator(@Bind("id") UUID id, @BindMethods("rule") AutomationRuleEvaluatorModel<T> rule);

    @SqlQuery("""
            SELECT rule.id, rule.project_id, rule.action, rule.name, rule.sampling_rate, evaluator.type, evaluator.code,
                   evaluator.created_at, evaluator.created_by, evaluator.last_updated_at, evaluator.last_updated_by
            FROM automation_rules rule
            JOIN automation_rule_evaluators evaluator
              ON rule.id = evaluator.id
            WHERE workspace_id = :workspaceId AND rule.action = :action
            <if(projectId)> AND project_id = :projectId <endif>
            <if(type)> AND evaluator.type = :type <endif>
            <if(ids)> AND rule.id IN (<ids>) <endif>
            <if(name)> AND rule.name like concat('%', :name, '%') <endif>
            ORDER by rule.id DESC
            <if(limit)> LIMIT :limit <endif>
            <if(offset)> OFFSET :offset <endif>
            """)
    @UseStringTemplateEngine
    @AllowUnusedBindings
    List<AutomationRuleEvaluatorModel<?>> find(@Bind("workspaceId") String workspaceId,
            @Define("projectId") @Bind("projectId") UUID projectId,
            @Bind("action") AutomationRule.AutomationRuleAction action,
            @Define("type") @Bind("type") AutomationRuleEvaluatorType type,
            @Define("ids") @BindList(onEmpty = BindList.EmptyHandling.NULL_VALUE, value = "ids") Set<UUID> ids,
            @Define("name") @Bind("name") String name,
            @Define("offset") @Bind("offset") Integer offset,
            @Define("limit") @Bind("limit") Integer limit);

    default List<AutomationRuleEvaluatorModel<?>> find(String workspaceId, UUID projectId,
            AutomationRuleEvaluatorCriteria criteria, Integer offset, Integer limit) {
        return find(workspaceId, projectId, criteria.action(), criteria.type(), criteria.ids(), criteria.name(), offset,
                limit);
    }

    default List<AutomationRuleEvaluatorModel<?>> find(String workspaceId, UUID projectId,
            AutomationRuleEvaluatorCriteria criteria) {
        return find(workspaceId, projectId, criteria, null, null);
    }

    @SqlQuery("""
            SELECT COUNT(*)
            FROM automation_rules rule
            JOIN automation_rule_evaluators evaluator
              ON rule.id = evaluator.id
            WHERE workspace_id = :workspaceId AND rule.action = :action
            <if(projectId)> AND project_id = :projectId <endif>
            <if(type)> AND evaluator.type = :type <endif>
            <if(ids)> AND rule.id IN (<ids>) <endif>
            <if(name)> AND rule.name like concat('%', :name, '%') <endif>
            """)
    @UseStringTemplateEngine
    @AllowUnusedBindings
    long findCount(
            @Bind("workspaceId") String workspaceId,
            @Define("projectId") @Bind("projectId") UUID projectId,
            @Bind("action") AutomationRule.AutomationRuleAction action,
            @Define("type") @Bind("type") AutomationRuleEvaluatorType type,
            @Define("ids") @BindList(onEmpty = BindList.EmptyHandling.NULL_VALUE, value = "ids") Set<UUID> ids,
            @Define("name") @Bind("name") String name);

    default long findCount(String workspaceId,
            UUID projectId,
            AutomationRuleEvaluatorCriteria criteria) {
        return findCount(workspaceId, projectId, criteria.action(), criteria.type(), criteria.ids(), criteria.name());
    }

    @SqlUpdate("""
                DELETE FROM automation_rule_evaluators
                WHERE id IN (
                    SELECT id
                    FROM automation_rules
                    WHERE workspace_id = :workspaceId AND project_id = :projectId
                    <if(ids)> AND id IN (<ids>) <endif>
                )
            """)
    @UseStringTemplateEngine
    @AllowUnusedBindings
    void deleteEvaluatorsByIds(@Bind("workspaceId") String workspaceId,
            @Bind("projectId") UUID projectId,
            @Define("ids") @BindList("ids") Set<UUID> ids);

}

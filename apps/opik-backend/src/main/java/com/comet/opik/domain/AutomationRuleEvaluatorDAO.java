package com.comet.opik.domain;

import com.comet.opik.api.AutomationRule;
import com.comet.opik.api.AutomationRuleEvaluatorUpdate;
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
@RegisterRowMapper(AutomationRuleEvaluatorRowMapper.class)
public interface AutomationRuleEvaluatorDAO extends AutomationRuleDAO {

    @SqlUpdate("INSERT INTO automation_rule_evaluators(id, `type`, code, created_by, last_updated_by) "+
               "VALUES (:rule.id, :rule.type, :rule.code, :rule.createdBy, :rule.lastUpdatedBy)")
    <T> void saveEvaluator(@BindMethods("rule") AutomationRuleEvaluatorModel<T> rule);

    @SqlUpdate("""
            UPDATE automation_rule_evaluators
            SET code = :rule.code,
                last_updated_by = :userName
            WHERE id = :id
            """)
    int updateEvaluator(@Bind("id") UUID id, @BindMethods("rule") AutomationRuleEvaluatorUpdate ruleUpdate, @Bind("userName") String userName);

    @SqlQuery("""
            SELECT rule.id, rule.project_id, rule.action, rule.name, rule.sampling_rate, evaluator.type, evaluator.code,
                   evaluator.created_at, evaluator.created_by, evaluator.last_updated_at, evaluator.last_updated_by
            FROM automation_rules rule
            JOIN automation_rule_evaluators evaluator
              ON rule.id = evaluator.id
            WHERE workspace_id = :workspaceId AND project_id = :projectId
            AND `action` = :action
            <if(ids)> AND rule.id IN (<ids>) <endif>
            <if(name)> AND name like concat('%', :name, '%') <endif>
            LIMIT :limit OFFSET :offset
            """)
    @UseStringTemplateEngine
    @AllowUnusedBindings
    List<AutomationRuleEvaluatorModel<?>> find(@Bind("workspaceId") String workspaceId,
                                               @Bind("projectId") UUID projectId,
                                               @Define("ids") @BindList(onEmpty = BindList.EmptyHandling.NULL_VALUE, value = "ids") Set<UUID> ids,
                                               @Define("name") @Bind("name") String name,
                                               @Bind("action") AutomationRule.AutomationRuleAction action,
                                               @Bind("offset") int offset,
                                               @Bind("limit") int limit);

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
                               @Define("ids") @BindList(onEmpty = BindList.EmptyHandling.NULL_VALUE, value = "ids") Set<UUID> ids);
}

package com.comet.opik.domain;

import com.comet.opik.api.AutomationRule;
import com.comet.opik.api.AutomationRuleEvaluator;
import com.comet.opik.api.AutomationRuleEvaluatorUpdate;
import com.comet.opik.infrastructure.db.UUIDArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.config.RegisterRowMapper;
import org.jdbi.v3.sqlobject.customizer.AllowUnusedBindings;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindMethods;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@RegisterArgumentFactory(UUIDArgumentFactory.class)
@RegisterRowMapper(AutomationRuleRowMapper.class)
@RegisterConstructorMapper(AutomationRuleEvaluator.class)
public interface AutomationRuleEvaluatorDAO extends AutomationRuleDAO {

    @SqlUpdate("INSERT INTO automation_rule_evaluators(id, `type`, code) "+
               "VALUES (:rule.id, :rule.type, :rule.code)")
    void save(@BindMethods("rule") AutomationRuleEvaluator rule);

    @SqlUpdate("""
            UPDATE automation_rule_evaluators
            SET code = :rule.code
            WHERE id = :id
            """)
    int update(@Bind("id") UUID id, @BindMethods("rule") AutomationRuleEvaluatorUpdate ruleUpdate);

    @SqlQuery("""
            SELECT rule.id, rule.project_id, rule.action, rule.sampling_rate, evaluator.type, evaluator.code, rule.created_at, rule.created_by, rule.last_updated_at, rule.last_updated_by
            FROM automation_rules rule
            JOIN automation_rule_evaluators evaluator
              ON rule.id = evaluator.id
            WHERE `action` = :action
            AND workspace_id = :workspaceId AND project_id = :projectId AND rule.id = :id
            """)
    Optional<AutomationRuleEvaluator> findById(@Bind("id") UUID id,
                                               @Bind("projectId") UUID projectId,
                                               @Bind("workspaceId") String workspaceId,
                                               @Bind("action") AutomationRule.AutomationRuleAction action);

    @SqlQuery("""
            SELECT rule.id, rule.project_id, rule.action, rule.sampling_rate, evaluator.type, evaluator.code, rule.created_at, rule.created_by, rule.last_updated_at, rule.last_updated_by
            FROM automation_rules rule
            JOIN automation_rule_evaluators evaluator
              ON rule.id = evaluator.id
            WHERE `action` = :action
            AND workspace_id = :workspaceId AND project_id = :projectId
            """)
    List<AutomationRuleEvaluator> findByProjectId(@Bind("projectId") UUID projectId, @Bind("workspaceId") String workspaceId, @Bind("action") AutomationRule.AutomationRuleAction action);

    @SqlQuery("""
            SELECT rule.id, rule.project_id, rule.action, rule.sampling_rate, evaluator.type, evaluator.code, rule.created_at, rule.created_by, rule.last_updated_at, rule.last_updated_by
            FROM automation_rules rule
            JOIN automation_rule_evaluators evaluator
              ON rule.id = evaluator.id
            WHERE `action` = :action
            AND workspace_id = :workspaceId AND project_id = :projectId
            LIMIT :limit OFFSET :offset
            """)
    @AllowUnusedBindings
    List<AutomationRuleEvaluator> find(@Bind("limit") int limit, @Bind("offset") int offset,
            @Bind("projectId") UUID projectId, @Bind("workspaceId") String workspaceId, @Bind("action") AutomationRule.AutomationRuleAction action);

}

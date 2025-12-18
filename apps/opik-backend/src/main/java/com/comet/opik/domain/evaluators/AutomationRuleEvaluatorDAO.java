package com.comet.opik.domain.evaluators;

import com.comet.opik.api.evaluators.AutomationRule;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorType;
import com.comet.opik.infrastructure.db.JsonNodeArgumentFactory;
import com.comet.opik.infrastructure.db.UUIDArgumentFactory;
import org.jdbi.v3.core.mapper.RowMapper;
import org.jdbi.v3.core.statement.StatementContext;
import org.jdbi.v3.sqlobject.config.RegisterArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterRowMapper;
import org.jdbi.v3.sqlobject.customizer.AllowUnusedBindings;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindList;
import org.jdbi.v3.sqlobject.customizer.BindMap;
import org.jdbi.v3.sqlobject.customizer.BindMethods;
import org.jdbi.v3.sqlobject.customizer.Define;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;
import org.jdbi.v3.stringtemplate4.UseStringTemplateEngine;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@RegisterArgumentFactory(UUIDArgumentFactory.class)
@RegisterArgumentFactory(JsonNodeArgumentFactory.class)
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

    /**
     * Query 1: Find rules WITHOUT project associations (clean, no duplication).
     * Returns one row per rule with all rule metadata.
     */
    @SqlQuery("""
            SELECT rule.id, rule.project_id AS legacy_project_id,
                   rule.action, rule.name AS name, rule.sampling_rate, rule.enabled, rule.filters,
                   evaluator.type, evaluator.code,
                   evaluator.created_at, evaluator.created_by, evaluator.last_updated_at, evaluator.last_updated_by
            FROM automation_rules rule
            JOIN automation_rule_evaluators evaluator ON rule.id = evaluator.id
            WHERE rule.workspace_id = :workspaceId AND rule.action = :action
            <if(projectIds)>
            AND (rule.project_id IN (<projectIds>) OR rule.id IN (
                SELECT DISTINCT rule_id
                FROM automation_rule_projects
                WHERE workspace_id = :workspaceId AND project_id IN (<projectIds>)
            ))
            <endif>
            <if(type)> AND evaluator.type = :type <endif>
            <if(ids)> AND rule.id IN (<ids>) <endif>
            <if(id)> AND rule.id like concat('%', :id, '%') <endif>
            <if(name)> AND rule.name like concat('%', :name, '%') <endif>
            <if(filters)> AND <filters> <endif>
            <if(sort_fields)> ORDER BY <sort_fields> <else> ORDER BY rule.id DESC <endif>
            <if(limit)> LIMIT :limit <endif>
            <if(offset)> OFFSET :offset <endif>
            """)
    @UseStringTemplateEngine
    @AllowUnusedBindings
    List<AutomationRuleEvaluatorModel<?>> findRulesWithoutProjects(
            @Bind("workspaceId") String workspaceId,
            @Define("projectIds") @BindList(onEmpty = BindList.EmptyHandling.NULL_VALUE, value = "projectIds") Set<UUID> projectIds,
            @Bind("action") AutomationRule.AutomationRuleAction action,
            @Define("type") @Bind("type") AutomationRuleEvaluatorType type,
            @Define("ids") @BindList(onEmpty = BindList.EmptyHandling.NULL_VALUE, value = "ids") Set<UUID> ids,
            @Define("id") @Bind("id") String id,
            @Define("name") @Bind("name") String name,
            @Define("sort_fields") @Bind("sort_fields") String sortingFields,
            @Define("filters") String filters,
            @BindMap Map<String, Object> filterMapping,
            @Define("offset") @Bind("offset") Integer offset,
            @Define("limit") @Bind("limit") Integer limit);

    /**
     * Query 2: Bulk fetch project associations for given rules.
     * Returns minimal data: only rule_id and project_id mappings.
     */
    @SqlQuery("""
            SELECT rule_id, project_id
            FROM automation_rule_projects
            WHERE rule_id IN (<ruleIds>) AND workspace_id = :workspaceId
            """)
    @UseStringTemplateEngine
    @RegisterRowMapper(RuleProjectMappingRowMapper.class)
    List<RuleProjectMapping> findProjectMappingsList(
            @BindList("ruleIds") List<UUID> ruleIds,
            @Bind("workspaceId") String workspaceId);

    /**
     * Helper to convert list of mappings into Map<RuleId, Set<ProjectId>>
     */
    default Map<UUID, Set<UUID>> findProjectMappings(List<UUID> ruleIds, String workspaceId) {
        return findProjectMappingsList(ruleIds, workspaceId).stream()
                .collect(Collectors.groupingBy(
                        RuleProjectMapping::ruleId,
                        Collectors.mapping(RuleProjectMapping::projectId, Collectors.toSet())));
    }

    /**
     * Simple record to hold rule-project mapping.
     */
    record RuleProjectMapping(UUID ruleId, UUID projectId) {
    }

    /**
     * Row mapper for RuleProjectMapping.
     */
    class RuleProjectMappingRowMapper implements RowMapper<RuleProjectMapping> {
        @Override
        public RuleProjectMapping map(ResultSet rs, StatementContext ctx)
                throws SQLException {
            return new RuleProjectMapping(
                    UUID.fromString(rs.getString("rule_id")),
                    UUID.fromString(rs.getString("project_id")));
        }
    }

    @SqlQuery("""
            SELECT COUNT(DISTINCT rule.id)
            FROM automation_rules rule
            JOIN automation_rule_evaluators evaluator
              ON rule.id = evaluator.id
            <if(projectIds)>
            LEFT JOIN automation_rule_projects arp
              ON rule.id = arp.rule_id AND rule.workspace_id = arp.workspace_id
            <endif>
            WHERE rule.workspace_id = :workspaceId AND rule.action = :action
            <if(projectIds)> AND (rule.project_id IN (<projectIds>) OR arp.project_id IN (<projectIds>)) <endif>
            <if(type)> AND evaluator.type = :type <endif>
            <if(ids)> AND rule.id IN (<ids>) <endif>
            <if(id)> AND rule.id like concat('%', :id, '%') <endif>
            <if(name)> AND rule.name like concat('%', :name, '%') <endif>
            """)
    @UseStringTemplateEngine
    @AllowUnusedBindings
    long findCount(
            @Bind("workspaceId") String workspaceId,
            @Define("projectIds") @BindList(onEmpty = BindList.EmptyHandling.NULL_VALUE, value = "projectIds") Set<UUID> projectIds,
            @Bind("action") AutomationRule.AutomationRuleAction action,
            @Define("type") @Bind("type") AutomationRuleEvaluatorType type,
            @Define("ids") @BindList(onEmpty = BindList.EmptyHandling.NULL_VALUE, value = "ids") Set<UUID> ids,
            @Define("id") @Bind("id") String id,
            @Define("name") @Bind("name") String name);

    default long findCount(String workspaceId,
            Set<UUID> projectIds,
            AutomationRuleEvaluatorCriteria criteria) {
        return findCount(workspaceId, projectIds, criteria.action(), criteria.type(), criteria.ids(),
                criteria.id(),
                criteria.name());
    }

    default long findCount(String workspaceId,
            UUID projectId,
            AutomationRuleEvaluatorCriteria criteria) {
        // Backward compatibility: convert single projectId to set
        return findCount(workspaceId, Optional.ofNullable(projectId).map(Set::of).orElse(null), criteria);
    }

    @SqlUpdate("""
                DELETE FROM automation_rule_evaluators
                WHERE id IN (
                    SELECT id
                    FROM automation_rules
                    WHERE workspace_id = :workspaceId
                    <if(ids)> AND id IN (<ids>) <endif>
                )
            """)
    @UseStringTemplateEngine
    @AllowUnusedBindings
    void deleteEvaluatorsByIds(@Bind("workspaceId") String workspaceId,
            @Define("ids") @BindList("ids") Set<UUID> ids);

}

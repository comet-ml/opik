package com.comet.opik.domain.evaluators;

import com.comet.opik.api.evaluators.AutomationRule;
import com.comet.opik.infrastructure.db.UUIDArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterRowMapper;
import org.jdbi.v3.sqlobject.customizer.AllowUnusedBindings;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindList;
import org.jdbi.v3.sqlobject.customizer.BindMethods;
import org.jdbi.v3.sqlobject.customizer.Define;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;
import org.jdbi.v3.stringtemplate4.UseStringTemplateEngine;

import java.util.Set;
import java.util.UUID;

@RegisterArgumentFactory(UUIDArgumentFactory.class)
@RegisterRowMapper(AutomationRuleRowMapper.class)
interface AutomationRuleDAO {

    @SqlUpdate("INSERT INTO automation_rules(id, workspace_id, `action`, name, sampling_rate, enabled, filters) "
            +
            "VALUES (:rule.id, :workspaceId, :rule.action, :rule.name, :rule.samplingRate, :rule.enabled, :rule.filters)")
    void saveBaseRule(@BindMethods("rule") AutomationRuleModel rule, @Bind("workspaceId") String workspaceId);

    @SqlUpdate("""
            UPDATE automation_rules
            SET name = :name,
                sampling_rate = :samplingRate,
                enabled = :enabled,
                filters = :filters
            WHERE id = :id AND workspace_id = :workspaceId
            """)
    int updateBaseRule(@Bind("id") UUID id,
            @Bind("workspaceId") String workspaceId,
            @Bind("name") String name,
            @Bind("samplingRate") float samplingRate,
            @Bind("enabled") boolean enabled,
            @Bind("filters") String filters);

    /**
     * Clears the legacy project_id field to prevent stale data.
     * Should be called when projects are removed from the junction table.
     */
    @SqlUpdate("UPDATE automation_rules SET project_id = NULL WHERE id = :id AND workspace_id = :workspaceId")
    int clearLegacyProjectId(@Bind("id") UUID id, @Bind("workspaceId") String workspaceId);

    @SqlUpdate("""
            DELETE FROM automation_rules
            WHERE workspace_id = :workspaceId
            <if(ids)> AND id IN (<ids>) <endif>
            """)
    @UseStringTemplateEngine
    @AllowUnusedBindings
    void deleteBaseRules(
            @Define("ids") @BindList(onEmpty = BindList.EmptyHandling.NULL_VALUE, value = "ids") Set<UUID> ids,
            @Bind("workspaceId") String workspaceId);

    @SqlQuery("""
            SELECT COUNT(DISTINCT rule.id)
            FROM automation_rules rule
            <if(projectIds)>
            LEFT JOIN automation_rule_projects arp ON rule.id = arp.rule_id
            <endif>
            WHERE rule.workspace_id = :workspaceId
            <if(projectIds)> AND arp.project_id IN (<projectIds>) <endif>
            <if(action)> AND rule.`action` = :action <endif>
            """)
    @UseStringTemplateEngine
    @AllowUnusedBindings
    long findCount(
            @Define("projectIds") @BindList(onEmpty = BindList.EmptyHandling.NULL_VALUE, value = "projectIds") Set<UUID> projectIds,
            @Bind("workspaceId") String workspaceId,
            @Define("action") @Bind("action") AutomationRule.AutomationRuleAction action);
}

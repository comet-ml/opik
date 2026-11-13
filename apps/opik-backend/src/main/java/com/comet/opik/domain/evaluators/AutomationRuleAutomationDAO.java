package com.comet.opik.domain.evaluators;

import com.comet.opik.infrastructure.db.EvalTriggerScopeColumnMapper;
import com.comet.opik.infrastructure.db.UUIDArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterColumnMapper;
import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.AllowUnusedBindings;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindList;
import org.jdbi.v3.sqlobject.customizer.Define;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;
import org.jdbi.v3.stringtemplate4.UseStringTemplateEngine;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * The {@code automation} subtype of {@code automation_rules}. Every read joins the parent for the columns
 * all rules share, and the junction table for the project, as the router DAO does.
 */
@RegisterConstructorMapper(AutomationRuleAutomationModel.class)
@RegisterColumnMapper(EvalTriggerScopeColumnMapper.class)
@RegisterArgumentFactory(UUIDArgumentFactory.class)
public interface AutomationRuleAutomationDAO {

    String SELECT = """
            SELECT rule.id, arp.project_id, rule.name, rule.sampling_rate, rule.enabled,
                   rule.trigger_scope, rule.filters,
                   a.triggers, a.steps, a.limits, a.recipe_id, a.recipe_version, a.created_via,
                   a.paused_until, a.paused_reason,
                   a.created_at, a.created_by, a.last_updated_at, a.last_updated_by
            FROM automation_rules rule
            JOIN automation_rule_automations a ON rule.id = a.id
            JOIN automation_rule_projects arp
                ON arp.rule_id = rule.id AND arp.workspace_id = rule.workspace_id
            """;

    // The event filter looks inside the triggers JSON: a trigger list is short, and an index on it would
    // only pay off once a project runs hundreds of automations.
    String FILTERS = """
            WHERE rule.workspace_id = :workspaceId
            <if(projectId)> AND arp.project_id = :projectId <endif>
            <if(enabled)> AND rule.enabled = :enabled <endif>
            <if(recipeId)> AND a.recipe_id = :recipeId <endif>
            <if(event)> AND JSON_SEARCH(a.triggers, 'one', :event, NULL, '$[*].event') IS NOT NULL <endif>
            """;

    @SqlUpdate("""
            INSERT INTO automation_rule_automations
                (id, triggers, steps, `limits`, recipe_id, recipe_version, created_via, created_by, last_updated_by)
            VALUES (:id, :triggers, :steps, :limits, :recipeId, :recipeVersion, :createdVia, :userName, :userName)
            ON DUPLICATE KEY UPDATE
                triggers = :triggers,
                steps = :steps,
                `limits` = :limits,
                last_updated_by = :userName
            """)
    void save(@Bind("id") UUID id,
            @Bind("triggers") String triggers,
            @Bind("steps") String steps,
            @Bind("limits") String limits,
            @Bind("recipeId") String recipeId,
            @Bind("recipeVersion") Integer recipeVersion,
            @Bind("createdVia") String createdVia,
            @Bind("userName") String userName);

    @SqlQuery(SELECT + "WHERE rule.workspace_id = :workspaceId AND rule.id IN (<ids>)")
    Stream<AutomationRuleAutomationModel> findByIds(@Bind("workspaceId") String workspaceId,
            @BindList("ids") List<UUID> ids);

    @SqlQuery(SELECT + FILTERS + " ORDER BY rule.id DESC LIMIT :limit OFFSET :offset")
    @UseStringTemplateEngine
    @AllowUnusedBindings
    List<AutomationRuleAutomationModel> find(@Bind("workspaceId") String workspaceId,
            @Define("projectId") @Bind("projectId") UUID projectId,
            @Define("enabled") @Bind("enabled") Boolean enabled,
            @Define("recipeId") @Bind("recipeId") String recipeId,
            @Define("event") @Bind("event") String event,
            @Bind("limit") int limit,
            @Bind("offset") int offset);

    @SqlQuery("SELECT COUNT(rule.id) FROM automation_rules rule "
            + "JOIN automation_rule_automations a ON rule.id = a.id "
            + "JOIN automation_rule_projects arp ON arp.rule_id = rule.id AND arp.workspace_id = rule.workspace_id "
            + FILTERS)
    @UseStringTemplateEngine
    @AllowUnusedBindings
    long count(@Bind("workspaceId") String workspaceId,
            @Define("projectId") @Bind("projectId") UUID projectId,
            @Define("enabled") @Bind("enabled") Boolean enabled,
            @Define("recipeId") @Bind("recipeId") String recipeId,
            @Define("event") @Bind("event") String event);

    /** Enabled automations of the given projects that listen to the event. Paused ones are included; the caller decides. */
    @SqlQuery(SELECT + """
            WHERE rule.workspace_id = :workspaceId
              AND rule.enabled = TRUE
              AND arp.project_id IN (<projectIds>)
              AND JSON_SEARCH(a.triggers, 'one', :event, NULL, '$[*].event') IS NOT NULL
            """)
    Stream<AutomationRuleAutomationModel> findEnabledByProjects(@Bind("workspaceId") String workspaceId,
            @BindList("projectIds") List<UUID> projectIds,
            @Bind("event") String event);

    @SqlQuery("""
            SELECT EXISTS (
                SELECT 1
                FROM automation_rules rule
                JOIN automation_rule_automations a ON rule.id = a.id
                WHERE rule.workspace_id = :workspaceId
                  AND rule.action = 'automation'
                  AND rule.enabled = TRUE
                  AND JSON_SEARCH(a.triggers, 'one', :event, NULL, '$[*].event') IS NOT NULL
                  AND EXISTS (
                      SELECT 1 FROM automation_rule_projects arp
                      WHERE arp.rule_id = rule.id AND arp.workspace_id = rule.workspace_id
                        AND arp.project_id = :projectId
                  )
            )
            """)
    boolean existsEnabledByProject(@Bind("workspaceId") String workspaceId,
            @Bind("projectId") UUID projectId,
            @Bind("event") String event);

    @SqlQuery("""
            SELECT EXISTS (
                SELECT 1
                FROM automation_rules rule
                JOIN automation_rule_automations a ON rule.id = a.id
                WHERE rule.workspace_id = :workspaceId
                  AND rule.action = 'automation'
                  AND rule.enabled = TRUE
                  AND JSON_SEARCH(a.triggers, 'one', :event, NULL, '$[*].event') IS NOT NULL
            )
            """)
    boolean existsEnabledByWorkspace(@Bind("workspaceId") String workspaceId, @Bind("event") String event);

    @SqlUpdate("""
            UPDATE automation_rule_automations
            SET paused_until = :pausedUntil, paused_reason = :pausedReason
            WHERE id = :id
            """)
    void pause(@Bind("id") UUID id, @Bind("pausedUntil") Instant pausedUntil,
            @Bind("pausedReason") String pausedReason);

    @SqlUpdate("DELETE FROM automation_rule_automations WHERE id IN (<ids>)")
    void deleteByIds(@BindList("ids") Set<UUID> ids);
}

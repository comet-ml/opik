package com.comet.opik.domain.evaluators;

import com.comet.opik.infrastructure.db.UUIDArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterRowMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindList;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * The {@code annotation_queue_router} subtype of {@code automation_rules}.
 *
 * <p>Every read joins the parent, because the columns a router shares with every other rule — workspace,
 * enabled, name — live there. Reads are addressed by queue rather than by rule id: the queue is what the
 * API has in hand, and the unique constraint on {@code queue_id} makes the lookup single-valued.
 */
@RegisterRowMapper(AutomationRuleAnnotationQueueRouterRowMapper.class)
@RegisterArgumentFactory(UUIDArgumentFactory.class)
public interface AutomationRuleAnnotationQueueRouterDAO {

    @SqlUpdate("""
            INSERT INTO automation_rule_annotation_queue_routers
                (id, queue_id, scope, conditions, max_items_in_queue, created_by, last_updated_by)
            VALUES (:id, :queueId, :scope, :conditions, :maxItemsInQueue, :userName, :userName)
            ON DUPLICATE KEY UPDATE
                scope = :scope,
                conditions = :conditions,
                max_items_in_queue = :maxItemsInQueue,
                last_updated_by = :userName
            """)
    void save(@Bind("id") UUID id,
            @Bind("queueId") UUID queueId,
            @Bind("scope") String scope,
            @Bind("conditions") String conditions,
            @Bind("maxItemsInQueue") Integer maxItemsInQueue,
            @Bind("userName") String userName);

    String SELECT_COLUMNS = """
            SELECT rule.id, arp.project_id, rule.name, rule.sampling_rate, rule.enabled,
                   rule.trigger_scope, rule.filters,
                   router.queue_id, router.scope, router.conditions, router.max_items_in_queue,
                   router.created_at, router.created_by, router.last_updated_at, router.last_updated_by
            FROM automation_rules rule
            JOIN automation_rule_annotation_queue_routers router ON rule.id = router.id
            JOIN automation_rule_projects arp
                ON arp.rule_id = rule.id AND arp.workspace_id = rule.workspace_id
            """;

    @SqlQuery(SELECT_COLUMNS + """
            WHERE rule.workspace_id = :workspaceId AND router.queue_id = :queueId
            """)
    Optional<AutomationRuleAnnotationQueueRouterModel> findByQueueId(@Bind("workspaceId") String workspaceId,
            @Bind("queueId") UUID queueId);

    @SqlQuery(SELECT_COLUMNS + """
            WHERE rule.workspace_id = :workspaceId AND router.queue_id = :queueId
            FOR UPDATE
            """)
    Optional<AutomationRuleAnnotationQueueRouterModel> findByQueueIdForUpdate(
            @Bind("workspaceId") String workspaceId, @Bind("queueId") UUID queueId);

    /**
     * Batch lookup for the queue list endpoint, so a page of queues costs one query rather than one per row.
     *
     * <p>Streamed rather than listed so the caller maps each row as it arrives. The stream is tied to the
     * handle, so it must be consumed inside the transaction that opened it.
     */
    @SqlQuery(SELECT_COLUMNS + """
            WHERE rule.workspace_id = :workspaceId AND router.queue_id IN (<queueIds>)
            """)
    Stream<AutomationRuleAnnotationQueueRouterModel> findByQueueIds(@Bind("workspaceId") String workspaceId,
            @BindList("queueIds") List<UUID> queueIds);

    /**
     * Enabled routers for the given projects — the scope routing actually runs at. Projects are reached
     * through the junction table as well as the legacy column, matching how evaluators are looked up.
     */
    @SqlQuery(SELECT_COLUMNS + """
            WHERE rule.workspace_id = :workspaceId
              AND rule.enabled = TRUE
              AND router.scope = :scope
              AND arp.project_id IN (<projectIds>)
            """)
    Stream<AutomationRuleAnnotationQueueRouterModel> findEnabledByProjects(
            @Bind("workspaceId") String workspaceId,
            @BindList("projectIds") List<UUID> projectIds,
            @Bind("scope") String scope);

    @SqlQuery("""
            SELECT EXISTS (
                SELECT 1
                FROM automation_rules rule
                JOIN automation_rule_annotation_queue_routers router ON rule.id = router.id
                WHERE rule.workspace_id = :workspaceId
                  AND rule.enabled = TRUE
                  AND router.scope = :scope
                  AND EXISTS (
                      SELECT 1 FROM automation_rule_projects arp
                      WHERE arp.rule_id = rule.id AND arp.workspace_id = rule.workspace_id
                        AND arp.project_id = :projectId
                  )
            )
            """)
    boolean existsEnabledByProject(@Bind("workspaceId") String workspaceId,
            @Bind("projectId") UUID projectId,
            @Bind("scope") String scope);

    /**
     * Coarser fallback guard, used only when the event carries no project id — which the batch score path
     * cannot, because one batch may span several projects.
     */
    @SqlQuery("""
            SELECT EXISTS (
                SELECT 1
                FROM automation_rules rule
                JOIN automation_rule_annotation_queue_routers router ON rule.id = router.id
                WHERE rule.workspace_id = :workspaceId AND rule.enabled = TRUE AND router.scope = :scope
            )
            """)
    boolean existsEnabledByWorkspace(@Bind("workspaceId") String workspaceId, @Bind("scope") String scope);

    @SqlQuery("""
            SELECT rule.id
            FROM automation_rules rule
            JOIN automation_rule_annotation_queue_routers router ON rule.id = router.id
            WHERE rule.workspace_id = :workspaceId AND router.queue_id IN (<queueIds>)
            """)
    List<UUID> findRuleIdsByQueueIds(@Bind("workspaceId") String workspaceId,
            @BindList("queueIds") List<UUID> queueIds);

    @SqlUpdate("""
            DELETE FROM automation_rule_annotation_queue_routers
            WHERE id IN (<ruleIds>)
            """)
    void deleteByRuleIds(@BindList("ruleIds") List<UUID> ruleIds);
}

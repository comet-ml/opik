package com.comet.opik.domain;

import com.comet.opik.infrastructure.db.UUIDArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindList;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@RegisterConstructorMapper(AnnotationQueueAutomationModel.class)
@RegisterArgumentFactory(UUIDArgumentFactory.class)
interface AnnotationQueueAutomationDAO {

    /**
     * Upsert, because the automation is edited through the queue's own create/update endpoints and the
     * caller does not track whether a row already exists.
     */
    @SqlUpdate("""
            INSERT INTO annotation_queue_automations
                (workspace_id, queue_id, project_id, scope, enabled, conditions, max_items_in_queue, created_by, last_updated_by)
            VALUES (:workspaceId, :queueId, :projectId, :scope, :enabled, :conditions, :maxItemsInQueue, :userName, :userName)
            ON DUPLICATE KEY UPDATE
                enabled = :enabled,
                conditions = :conditions,
                max_items_in_queue = :maxItemsInQueue,
                last_updated_by = :userName
            """)
    void save(@Bind("workspaceId") String workspaceId,
            @Bind("queueId") UUID queueId,
            @Bind("projectId") UUID projectId,
            @Bind("scope") String scope,
            @Bind("enabled") boolean enabled,
            @Bind("conditions") String conditions,
            @Bind("maxItemsInQueue") Integer maxItemsInQueue,
            @Bind("userName") String userName);

    @SqlQuery("""
            SELECT * FROM annotation_queue_automations
            WHERE workspace_id = :workspaceId AND queue_id = :queueId
            """)
    Optional<AnnotationQueueAutomationModel> findByQueueId(@Bind("workspaceId") String workspaceId,
            @Bind("queueId") UUID queueId);

    /**
     * The same row, locked for the caller's transaction.
     *
     * <p>Saving resolves omitted fields from what is already stored and then rewrites the whole row, so a
     * non-locking read would let two concurrent edits both resolve against the same snapshot and the later
     * write restore values the earlier one had just changed.
     */
    @SqlQuery("""
            SELECT * FROM annotation_queue_automations
            WHERE workspace_id = :workspaceId AND queue_id = :queueId
            FOR UPDATE
            """)
    Optional<AnnotationQueueAutomationModel> findByQueueIdForUpdate(@Bind("workspaceId") String workspaceId,
            @Bind("queueId") UUID queueId);

    /**
     * Batch lookup for the queue list endpoint, so a page of queues costs one query rather than one per row.
     */
    @SqlQuery("""
            SELECT * FROM annotation_queue_automations
            WHERE workspace_id = :workspaceId AND queue_id IN (<queueIds>)
            """)
    List<AnnotationQueueAutomationModel> findByQueueIds(@Bind("workspaceId") String workspaceId,
            @BindList("queueIds") List<UUID> queueIds);

    @SqlUpdate("""
            DELETE FROM annotation_queue_automations
            WHERE workspace_id = :workspaceId AND queue_id IN (<queueIds>)
            """)
    void deleteByQueueIds(@Bind("workspaceId") String workspaceId,
            @BindList("queueIds") List<UUID> queueIds);

    /**
     * Enabled automations for specific projects — the authoritative scope, since an automation belongs to a
     * queue and a queue belongs to a project. The consumer derives the project ids from the entities' own
     * scores, so it never has to consider automations from projects the event has nothing to do with.
     */
    @SqlQuery("""
            SELECT * FROM annotation_queue_automations
            WHERE workspace_id = :workspaceId
              AND project_id IN (<projectIds>)
              AND enabled = TRUE
              AND scope = :scope
            """)
    List<AnnotationQueueAutomationModel> findEnabledByProjects(@Bind("workspaceId") String workspaceId,
            @BindList("projectIds") List<UUID> projectIds,
            @Bind("scope") String scope);

    /**
     * Guard for the event listener when the event names its project. Only a yes/no, so it avoids returning
     * and deserialising conditions JSON on an event that will be dropped.
     */
    @SqlQuery("""
            SELECT EXISTS (
                SELECT 1 FROM annotation_queue_automations
                WHERE workspace_id = :workspaceId
                  AND project_id = :projectId
                  AND enabled = TRUE
                  AND scope = :scope
            )
            """)
    boolean existsEnabledByProject(@Bind("workspaceId") String workspaceId,
            @Bind("projectId") UUID projectId,
            @Bind("scope") String scope);

    /**
     * Coarser fallback guard, used only when the event carries no project id — which the batch score path
     * cannot, because one batch may span several projects. This is a pre-filter to avoid publishing for
     * workspaces that have no automation at all; it is <strong>not</strong> the feature's scope. The
     * project scope is enforced by {@link #findEnabledByProjects} in the consumer.
     */
    @SqlQuery("""
            SELECT EXISTS (
                SELECT 1 FROM annotation_queue_automations
                WHERE workspace_id = :workspaceId AND enabled = TRUE AND scope = :scope
            )
            """)
    boolean existsEnabledByWorkspace(@Bind("workspaceId") String workspaceId, @Bind("scope") String scope);
}

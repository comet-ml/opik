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
                (workspace_id, queue_id, project_id, scope, enabled, conditions, created_by, last_updated_by)
            VALUES (:workspaceId, :queueId, :projectId, :scope, :enabled, :conditions, :userName, :userName)
            ON DUPLICATE KEY UPDATE
                enabled = :enabled,
                conditions = :conditions,
                last_updated_by = :userName
            """)
    void save(@Bind("workspaceId") String workspaceId,
            @Bind("queueId") UUID queueId,
            @Bind("projectId") UUID projectId,
            @Bind("scope") String scope,
            @Bind("enabled") boolean enabled,
            @Bind("conditions") String conditions,
            @Bind("userName") String userName);

    @SqlQuery("""
            SELECT * FROM annotation_queue_automations
            WHERE workspace_id = :workspaceId AND queue_id = :queueId
            """)
    Optional<AnnotationQueueAutomationModel> findByQueueId(@Bind("workspaceId") String workspaceId,
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
}

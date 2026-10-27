package com.comet.opik.domain.topics;

import com.comet.opik.infrastructure.db.UUIDArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindList;
import org.jdbi.v3.sqlobject.customizer.BindMethods;
import org.jdbi.v3.sqlobject.statement.SqlBatch;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * A project's topic list: {@code topics}, {@code topic_prototypes} and {@code topic_project_state}. A nightly run writes
 * all three in its save transaction. An empty id list matches nothing.
 */
@RegisterArgumentFactory(UUIDArgumentFactory.class)
@RegisterConstructorMapper(Topic.class)
@RegisterConstructorMapper(TopicPrototype.class)
@RegisterConstructorMapper(TopicProjectState.class)
public interface TopicDAO {

    @SqlBatch("""
            INSERT INTO topics (id, workspace_id, project_id, name, definition, born, retired_at, retired_reason,
                inactive_since, low_nights, lo7, hi7, size)
            VALUES (:id, :workspaceId, :projectId, :name, :definition, :born, :retiredAt, :retiredReason,
                :inactiveSince, :lowNights, :lo7, :hi7, :size)
            """)
    void insertTopics(@BindMethods List<Topic> topics);

    @SqlQuery("""
            SELECT * FROM topics
            WHERE project_id = :projectId AND retired_at IS NULL
            ORDER BY born, id
            """)
    List<Topic> findLive(@Bind("projectId") UUID projectId);

    @SqlUpdate("DELETE FROM topic_prototypes WHERE project_id = :projectId")
    int deletePrototypes(@Bind("projectId") UUID projectId);

    @SqlBatch("""
            INSERT INTO topic_prototypes (id, workspace_id, project_id, position, topic_id, vector)
            VALUES (:id, :workspaceId, :projectId, :position, :topicId, :vector)
            """)
    void insertPrototypes(@BindMethods List<TopicPrototype> prototypes);

    @SqlQuery("SELECT * FROM topic_prototypes WHERE project_id = :projectId ORDER BY position")
    List<TopicPrototype> findPrototypes(@Bind("projectId") UUID projectId);

    @SqlUpdate("""
            INSERT INTO topic_project_state (project_id, workspace_id, rebuilds, first_built_at, last_run_id,
                last_success_at, window_until)
            VALUES (:projectId, :workspaceId, :rebuilds, :firstBuiltAt, :lastRunId, :lastSuccessAt, :windowUntil)
            ON DUPLICATE KEY UPDATE rebuilds = VALUES(rebuilds), first_built_at = VALUES(first_built_at),
                last_run_id = VALUES(last_run_id), last_success_at = VALUES(last_success_at),
                window_until = VALUES(window_until)
            """)
    void upsertState(@BindMethods TopicProjectState state);

    @SqlQuery("SELECT * FROM topic_project_state WHERE project_id IN (<projectIds>)")
    List<TopicProjectState> findStates(
            @BindList(value = "projectIds", onEmpty = BindList.EmptyHandling.NULL_STRING) Collection<UUID> projectIds);
}

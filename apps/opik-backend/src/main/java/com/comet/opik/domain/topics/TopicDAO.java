package com.comet.opik.domain.topics;

import com.comet.opik.infrastructure.db.UUIDArgumentFactory;
import lombok.NonNull;
import org.jdbi.v3.sqlobject.config.RegisterArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindList;
import org.jdbi.v3.sqlobject.customizer.BindMethods;
import org.jdbi.v3.sqlobject.statement.SqlBatch;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * A project's topic list: {@code topics}, {@code topic_prototypes} and {@code topic_project_states}. A run writes all
 * three in its save transaction. An empty id list matches nothing.
 */
@RegisterArgumentFactory(UUIDArgumentFactory.class)
@RegisterConstructorMapper(Topic.class)
@RegisterConstructorMapper(TopicPrototype.class)
@RegisterConstructorMapper(TopicProjectState.class)
@RegisterConstructorMapper(TopicDAO.ProjectKey.class)
public interface TopicDAO {

    @SqlBatch("""
            INSERT INTO topics (id, workspace_id, project_id, name, definition, born, retired_at, retired_reason,
                low_nights, size)
            VALUES (:id, :workspaceId, :projectId, :name, :definition, :born, :retiredAt, :retiredReason,
                :lowNights, :size)
            """)
    void insertTopics(@BindMethods List<Topic> topics);

    @SqlQuery("""
            SELECT * FROM topics
            WHERE workspace_id = :workspaceId AND project_id = :projectId AND retired_at IS NULL
            ORDER BY born, id
            """)
    List<Topic> findLive(@Bind("workspaceId") String workspaceId, @Bind("projectId") UUID projectId);

    @SqlBatch("""
            INSERT INTO topic_prototypes (workspace_id, project_id, position, topic_id, vector)
            VALUES (:workspaceId, :projectId, :position, :topicId, :vector)
            """)
    void insertPrototypes(@BindMethods List<TopicPrototype> prototypes);

    @SqlQuery("""
            SELECT * FROM topic_prototypes
            WHERE workspace_id = :workspaceId AND project_id = :projectId
            ORDER BY position
            """)
    List<TopicPrototype> findPrototypes(@Bind("workspaceId") String workspaceId, @Bind("projectId") UUID projectId);

    /** The first save of a project: a second run that saved meanwhile fails it on the primary key. */
    @SqlUpdate("""
            INSERT INTO topic_project_states (project_id, workspace_id, rebuilds, first_built_at, last_run_id,
                last_success_at, window_until)
            VALUES (:projectId, :workspaceId, :rebuilds, :firstBuiltAt, :lastRunId, :lastSuccessAt, :windowUntil)
            """)
    void insertState(@BindMethods TopicProjectState state);

    @SqlUpdate("DELETE FROM topics WHERE workspace_id = :workspaceId AND project_id IN (<projectIds>)")
    int deleteTopicsByProjects(@Bind("workspaceId") String workspaceId,
            @BindList("projectIds") Collection<UUID> projectIds);

    @SqlUpdate("DELETE FROM topic_prototypes WHERE workspace_id = :workspaceId AND project_id IN (<projectIds>)")
    int deletePrototypesByProjects(@Bind("workspaceId") String workspaceId,
            @BindList("projectIds") Collection<UUID> projectIds);

    @SqlUpdate("DELETE FROM topic_project_states WHERE workspace_id = :workspaceId AND project_id IN (<projectIds>)")
    int deleteStatesByProjects(@Bind("workspaceId") String workspaceId,
            @BindList("projectIds") Collection<UUID> projectIds);

    @SqlQuery("SELECT * FROM topic_project_states WHERE workspace_id = :workspaceId AND project_id IN (<projectIds>)")
    List<TopicProjectState> findStates(@Bind("workspaceId") String workspaceId,
            @BindList(value = "projectIds", onEmpty = BindList.EmptyHandling.NULL_STRING) Collection<UUID> projectIds);

    /** Holds a shared lock on the project's row until the transaction ends, so a project delete waits for it. */
    @SqlQuery("SELECT COUNT(*) FROM projects WHERE workspace_id = :workspaceId AND id = :projectId FOR SHARE")
    int lockProject(@Bind("workspaceId") String workspaceId, @Bind("projectId") UUID projectId);

    /**
     * Of the projects of {@code workspaceIds}, those with no saved list and a trace written at or after {@code since}:
     * the ones a first build may be due for.
     */
    @SqlQuery("""
            SELECT p.workspace_id, p.id AS project_id FROM projects p
            WHERE p.workspace_id IN (<workspaceIds>) AND p.last_updated_trace_at >= :since
            AND NOT EXISTS (
                SELECT 1 FROM topic_project_states s WHERE s.workspace_id = p.workspace_id AND s.project_id = p.id
            )
            """)
    List<ProjectKey> findListlessProjects(@BindList("workspaceIds") Collection<String> workspaceIds,
            @Bind("since") Instant since);

    record ProjectKey(@NonNull String workspaceId, @NonNull UUID projectId) {
    }
}

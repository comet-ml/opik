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
import java.util.Set;
import java.util.UUID;

/**
 * A project's topic list: {@code topics}, {@code topic_prototypes} and {@code topic_project_states}. A nightly run writes
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

    /** The nightly update's save of a topic it read: everything but the id, name and born can change. */
    @SqlBatch("""
            UPDATE topics SET definition = :definition, retired_at = :retiredAt, retired_reason = :retiredReason,
                inactive_since = :inactiveSince, low_nights = :lowNights, lo7 = :lo7, hi7 = :hi7, size = :size
            WHERE id = :id AND project_id = :projectId
            """)
    int[] updateTopics(@BindMethods List<Topic> topics);

    /** The projects among {@code projectIds} with at least one live topic. */
    @SqlQuery("""
            SELECT DISTINCT project_id FROM topics
            WHERE project_id IN (<projectIds>) AND retired_at IS NULL
            """)
    List<UUID> findProjectsWithLiveTopics(
            @BindList(value = "projectIds", onEmpty = BindList.EmptyHandling.NULL_STRING) Collection<UUID> projectIds);

    @SqlQuery("SELECT id FROM topics WHERE project_id = :projectId AND retired_at IS NOT NULL")
    List<UUID> findRetiredIds(@Bind("projectId") UUID projectId);

    @SqlUpdate("DELETE FROM topic_prototypes WHERE project_id = :projectId")
    int deletePrototypes(@Bind("projectId") UUID projectId);

    @SqlBatch("""
            INSERT INTO topic_prototypes (id, workspace_id, project_id, position, topic_id, vector)
            VALUES (:id, :workspaceId, :projectId, :position, :topicId, :vector)
            """)
    void insertPrototypes(@BindMethods List<TopicPrototype> prototypes);

    @SqlQuery("SELECT * FROM topic_prototypes WHERE project_id = :projectId ORDER BY position")
    List<TopicPrototype> findPrototypes(@Bind("projectId") UUID projectId);

    /** The first save of a project: a second run that saved meanwhile fails it on the primary key. */
    @SqlUpdate("""
            INSERT INTO topic_project_states (project_id, workspace_id, rebuilds, first_built_at, last_run_id,
                last_success_at, window_until)
            VALUES (:projectId, :workspaceId, :rebuilds, :firstBuiltAt, :lastRunId, :lastSuccessAt, :windowUntil)
            """)
    void insertState(@BindMethods TopicProjectState state);

    /**
     * A later save: updates the row only while its {@code last_run_id} is still the one the run read, so a run that
     * saved meanwhile makes this return 0. The Redis project lock alone can be lost.
     */
    @SqlUpdate("""
            UPDATE topic_project_states
            SET rebuilds = :rebuilds, first_built_at = :firstBuiltAt, last_run_id = :lastRunId,
                last_success_at = :lastSuccessAt, window_until = :windowUntil
            WHERE project_id = :projectId AND last_run_id <=> :expectedRunId
            """)
    int updateState(@BindMethods TopicProjectState state, @Bind("expectedRunId") UUID expectedRunId);

    @SqlUpdate("DELETE FROM topics WHERE workspace_id = :workspaceId AND project_id IN (<projectIds>)")
    int deleteTopicsByProjects(@Bind("workspaceId") String workspaceId,
            @BindList("projectIds") Collection<UUID> projectIds);

    @SqlUpdate("DELETE FROM topic_prototypes WHERE workspace_id = :workspaceId AND project_id IN (<projectIds>)")
    int deletePrototypesByProjects(@Bind("workspaceId") String workspaceId,
            @BindList("projectIds") Collection<UUID> projectIds);

    @SqlUpdate("DELETE FROM topic_project_states WHERE workspace_id = :workspaceId AND project_id IN (<projectIds>)")
    int deleteStatesByProjects(@Bind("workspaceId") String workspaceId,
            @BindList("projectIds") Collection<UUID> projectIds);

    @SqlQuery("SELECT * FROM topic_project_states WHERE project_id IN (<projectIds>)")
    List<TopicProjectState> findStates(
            @BindList(value = "projectIds", onEmpty = BindList.EmptyHandling.NULL_STRING) Collection<UUID> projectIds);

    @SqlQuery("SELECT id FROM projects WHERE workspace_id = :workspaceId AND id IN (<projectIds>)")
    Set<UUID> findExistingProjectIds(@Bind("workspaceId") String workspaceId,
            @BindList("projectIds") Collection<UUID> projectIds);
}

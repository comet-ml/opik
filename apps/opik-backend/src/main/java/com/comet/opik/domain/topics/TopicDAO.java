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
import java.util.Set;
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

    /**
     * The nightly update's save of a topic it read. Only {@code retired_at}, {@code retired_reason},
     * {@code low_nights} and {@code size} can change: the night never changes a topic's id, name, definition or born.
     */
    @SqlBatch("""
            UPDATE topics SET retired_at = :retiredAt, retired_reason = :retiredReason, low_nights = :lowNights,
                size = :size
            WHERE workspace_id = :workspaceId AND project_id = :projectId AND id = :id
            """)
    int[] updateTopics(@BindMethods List<Topic> topics);

    /** The projects among {@code projectIds} of the workspace with at least one live topic. */
    @SqlQuery("""
            SELECT DISTINCT project_id FROM topics
            WHERE workspace_id = :workspaceId AND project_id IN (<projectIds>) AND retired_at IS NULL
            """)
    List<UUID> findProjectsWithLiveTopics(@Bind("workspaceId") String workspaceId,
            @BindList(value = "projectIds", onEmpty = BindList.EmptyHandling.NULL_STRING) Collection<UUID> projectIds);

    /** The projects of {@code workspaceIds} with at least one live topic: the nightly update's candidates. */
    @SqlQuery("""
            SELECT DISTINCT workspace_id, project_id FROM topics
            WHERE workspace_id IN (<workspaceIds>) AND retired_at IS NULL
            """)
    List<ProjectKey> findProjectsWithLiveTopics(@BindList("workspaceIds") Collection<String> workspaceIds);

    @SqlUpdate("DELETE FROM topic_prototypes WHERE workspace_id = :workspaceId AND project_id = :projectId")
    int deletePrototypes(@Bind("workspaceId") String workspaceId, @Bind("projectId") UUID projectId);

    /**
     * Deletes the project's state row: a nightly save that leaves no live topic, so the project has no list again and
     * the first-build job builds it.
     */
    @SqlUpdate("DELETE FROM topic_project_states WHERE workspace_id = :workspaceId AND project_id = :projectId")
    int deleteState(@Bind("workspaceId") String workspaceId, @Bind("projectId") UUID projectId);

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

    /**
     * The prototype rows of several projects of one workspace, in one query on the primary key: by project, each in
     * {@code position} order. Topic on arrival reads its projects' saved lists with it.
     */
    @SqlQuery("""
            SELECT * FROM topic_prototypes
            WHERE workspace_id = :workspaceId AND project_id IN (<projectIds>)
            ORDER BY project_id, position
            """)
    List<TopicPrototype> findPrototypes(@Bind("workspaceId") String workspaceId,
            @BindList(value = "projectIds", onEmpty = BindList.EmptyHandling.NULL_STRING) Collection<UUID> projectIds);

    /** The first save of a project: a second run that saved meanwhile fails it on the primary key. */
    @SqlUpdate("""
            INSERT INTO topic_project_states (project_id, workspace_id, rebuilds, first_built_at, last_run_id,
                last_success_at, window_until, labels_complete)
            VALUES (:projectId, :workspaceId, :rebuilds, :firstBuiltAt, :lastRunId, :lastSuccessAt, :windowUntil,
                :labelsComplete)
            """)
    void insertState(@BindMethods TopicProjectState state);

    /**
     * A later save: updates the row only while its {@code last_run_id} is still the one the run read, so a run that
     * saved meanwhile makes this return 0. The Redis project lock alone can be lost.
     */
    @SqlUpdate("""
            UPDATE topic_project_states
            SET rebuilds = :rebuilds, first_built_at = :firstBuiltAt, last_run_id = :lastRunId,
                last_success_at = :lastSuccessAt, window_until = :windowUntil, labels_complete = :labelsComplete
            WHERE workspace_id = :workspaceId AND project_id = :projectId AND last_run_id <=> :expectedRunId
            """)
    int updateState(@BindMethods TopicProjectState state, @Bind("expectedRunId") UUID expectedRunId);

    /**
     * Marks the labels of the project's last saved run complete. Only while the state still names {@code runId}: a
     * pass that labelled an older run never marks a newer one. 0 when the state names another run or is gone.
     */
    @SqlUpdate("""
            UPDATE topic_project_states SET labels_complete = TRUE
            WHERE workspace_id = :workspaceId AND project_id = :projectId AND last_run_id = :runId
            """)
    int markLabelsComplete(@Bind("workspaceId") String workspaceId, @Bind("projectId") UUID projectId,
            @Bind("runId") UUID runId);

    /** The projects of {@code workspaceIds} whose last saved run's labels are not complete, the oldest save first. */
    @SqlQuery("""
            SELECT workspace_id, project_id FROM topic_project_states
            WHERE workspace_id IN (<workspaceIds>) AND labels_complete = FALSE
            ORDER BY last_success_at, project_id
            """)
    List<ProjectKey> findLabelsIncomplete(@BindList("workspaceIds") Collection<String> workspaceIds);

    @SqlUpdate("DELETE FROM topics WHERE workspace_id = :workspaceId AND project_id IN (<projectIds>)")
    int deleteTopicsByProjects(@Bind("workspaceId") String workspaceId,
            @BindList("projectIds") Collection<UUID> projectIds);

    @SqlUpdate("DELETE FROM topic_prototypes WHERE workspace_id = :workspaceId AND project_id IN (<projectIds>)")
    int deletePrototypesByProjects(@Bind("workspaceId") String workspaceId,
            @BindList("projectIds") Collection<UUID> projectIds);

    @SqlUpdate("DELETE FROM topic_project_states WHERE workspace_id = :workspaceId AND project_id IN (<projectIds>)")
    int deleteStatesByProjects(@Bind("workspaceId") String workspaceId,
            @BindList("projectIds") Collection<UUID> projectIds);

    /**
     * Deletes the workspaces' topics whose project no longer exists: rows a failed delete on {@code ProjectsDeleted}
     * left behind, or a save that committed after the project was deleted.
     */
    @SqlUpdate("""
            DELETE t FROM topics t
            LEFT JOIN projects p ON p.id = t.project_id AND p.workspace_id = t.workspace_id
            WHERE t.workspace_id IN (<workspaceIds>) AND p.id IS NULL
            """)
    int deleteTopicsOfMissingProjects(@BindList("workspaceIds") Collection<String> workspaceIds);

    /** As {@link #deleteTopicsOfMissingProjects}, for {@code topic_prototypes}. */
    @SqlUpdate("""
            DELETE t FROM topic_prototypes t
            LEFT JOIN projects p ON p.id = t.project_id AND p.workspace_id = t.workspace_id
            WHERE t.workspace_id IN (<workspaceIds>) AND p.id IS NULL
            """)
    int deletePrototypesOfMissingProjects(@BindList("workspaceIds") Collection<String> workspaceIds);

    /** As {@link #deleteTopicsOfMissingProjects}, for {@code topic_project_states}. */
    @SqlUpdate("""
            DELETE t FROM topic_project_states t
            LEFT JOIN projects p ON p.id = t.project_id AND p.workspace_id = t.workspace_id
            WHERE t.workspace_id IN (<workspaceIds>) AND p.id IS NULL
            """)
    int deleteStatesOfMissingProjects(@BindList("workspaceIds") Collection<String> workspaceIds);

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

    @SqlQuery("SELECT id FROM projects WHERE workspace_id = :workspaceId AND id IN (<projectIds>)")
    Set<UUID> findExistingProjectIds(@Bind("workspaceId") String workspaceId,
            @BindList("projectIds") Collection<UUID> projectIds);

    record ProjectKey(@NonNull String workspaceId, @NonNull UUID projectId) {
    }
}

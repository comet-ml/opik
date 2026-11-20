package com.comet.opik.domain.topics;

import com.comet.opik.infrastructure.db.PrototypeVectorsArgumentFactory;
import com.comet.opik.infrastructure.db.PrototypeVectorsColumnMapper;
import com.comet.opik.infrastructure.db.UUIDArgumentFactory;
import jakarta.annotation.Nullable;
import lombok.NonNull;
import org.jdbi.v3.sqlobject.config.RegisterArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterColumnMapper;
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
 * A project's topic list: its {@code topics} rows and the list fields of its {@code topic_projects} row, which a run's
 * save writes in one transaction. The attempt fields are {@link TopicRunDAO}'s, the backfill fields and the row's
 * delete {@link TopicVectorBackfillDAO}'s. An empty id list matches nothing.
 */
@RegisterArgumentFactory(UUIDArgumentFactory.class)
@RegisterArgumentFactory(PrototypeVectorsArgumentFactory.class)
@RegisterColumnMapper(PrototypeVectorsColumnMapper.class)
@RegisterColumnMapper(TopicRunDAO.RunModeMapper.class)
@RegisterColumnMapper(TopicRunDAO.RunStatusMapper.class)
@RegisterColumnMapper(TopicRunDAO.RunErrorMapper.class)
@RegisterColumnMapper(TopicRunDAO.CancelReasonMapper.class)
@RegisterConstructorMapper(Topic.class)
@RegisterConstructorMapper(TopicProject.class)
@RegisterConstructorMapper(TopicDAO.ProjectKey.class)
public interface TopicDAO {

    @SqlBatch("""
            INSERT INTO topics (id, workspace_id, project_id, name, definition, born, low_nights, size, vectors)
            VALUES (:id, :workspaceId, :projectId, :name, :definition, :born, :lowNights, :size, :vectors)
            """)
    void insertTopics(@BindMethods List<Topic> topics);

    /** The project's topics in the order assignment breaks ties by. */
    @SqlQuery("""
            SELECT * FROM topics
            WHERE workspace_id = :workspaceId AND project_id = :projectId
            ORDER BY born, id
            """)
    List<Topic> findByProject(@Bind("workspaceId") String workspaceId, @Bind("projectId") UUID projectId);

    /**
     * Saves {@code runId}'s list on the project's row: a compare-and-set on {@code list_run_id}, which must still be
     * {@code expectedListRunId}, null for a first build. 0 when another run saved the project meanwhile, or the row is
     * gone. {@code saved_runs} counts every save; {@code first_built_at} keeps the first.
     */
    @SqlUpdate("""
            UPDATE topic_projects
            SET saved_runs = saved_runs + 1, first_built_at = COALESCE(first_built_at, :savedAt),
                last_success_at = :savedAt, list_run_id = :runId, window_until = :windowUntil,
                labels_complete = FALSE, other_vectors = :otherVectors, embed_model = :embedModel
            WHERE workspace_id = :workspaceId AND project_id = :projectId AND list_run_id <=> :expectedListRunId
            """)
    int saveList(@Bind("workspaceId") String workspaceId, @Bind("projectId") UUID projectId,
            @Nullable @Bind("expectedListRunId") UUID expectedListRunId, @Bind("runId") UUID runId,
            @Bind("savedAt") Instant savedAt, @Bind("windowUntil") Instant windowUntil,
            @Nullable @Bind("otherVectors") PrototypeVectors otherVectors, @Bind("embedModel") String embedModel);

    /**
     * Marks the labels of the project's saved list complete. Only while the row still names {@code runId}: a pass that
     * labelled an older run never marks a newer one. 0 when the row names another run or is gone.
     */
    @SqlUpdate("""
            UPDATE topic_projects SET labels_complete = TRUE
            WHERE workspace_id = :workspaceId AND project_id = :projectId AND list_run_id = :runId
            """)
    int markLabelsComplete(@Bind("workspaceId") String workspaceId, @Bind("projectId") UUID projectId,
            @Bind("runId") UUID runId);

    @SqlQuery("SELECT * FROM topic_projects WHERE workspace_id = :workspaceId AND project_id IN (<projectIds>)")
    List<TopicProject> findProjects(@Bind("workspaceId") String workspaceId,
            @BindList(value = "projectIds", onEmpty = BindList.EmptyHandling.NULL_STRING) Collection<UUID> projectIds);

    @SqlUpdate("DELETE FROM topics WHERE workspace_id = :workspaceId AND project_id IN (<projectIds>)")
    int deleteTopicsByProjects(@Bind("workspaceId") String workspaceId,
            @BindList("projectIds") Collection<UUID> projectIds);

    /** Deletes the workspaces' topics whose project no longer exists. */
    @SqlUpdate("""
            DELETE t FROM topics t
            LEFT JOIN projects p ON p.id = t.project_id AND p.workspace_id = t.workspace_id
            WHERE t.workspace_id IN (<workspaceIds>) AND p.id IS NULL
            """)
    int deleteTopicsOfMissingProjects(@BindList("workspaceIds") Collection<String> workspaceIds);

    /** Holds a shared lock on the project's row until the transaction ends, so a project delete waits for it. */
    @SqlQuery("SELECT COUNT(*) FROM projects WHERE workspace_id = :workspaceId AND id = :projectId FOR SHARE")
    int lockProject(@Bind("workspaceId") String workspaceId, @Bind("projectId") UUID projectId);

    record ProjectKey(@NonNull String workspaceId, @NonNull UUID projectId) {
    }
}

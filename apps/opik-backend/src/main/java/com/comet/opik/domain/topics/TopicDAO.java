package com.comet.opik.domain.topics;

import com.comet.opik.infrastructure.db.PrototypeVectorsArgumentFactory;
import com.comet.opik.infrastructure.db.PrototypeVectorsColumnMapper;
import com.comet.opik.infrastructure.db.UUIDArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterColumnMapper;
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
 * A project's topics, the {@code topics} rows. A run's save writes them in one transaction with the list fields of the
 * project's {@code topic_projects} row ({@link TopicProjectDAO}).
 */
@RegisterArgumentFactory(UUIDArgumentFactory.class)
@RegisterArgumentFactory(PrototypeVectorsArgumentFactory.class)
@RegisterColumnMapper(PrototypeVectorsColumnMapper.class)
@RegisterConstructorMapper(Topic.class)
@RegisterConstructorMapper(TopicProjectKey.class)
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

    @SqlUpdate("DELETE FROM topics WHERE workspace_id = :workspaceId AND project_id IN (<projectIds>)")
    int deleteTopicsByProjects(@Bind("workspaceId") String workspaceId,
            @BindList("projectIds") Collection<UUID> projectIds);

    /**
     * The workspaces' projects that have topics and no longer exist, for the caller to delete by
     * {@link #deleteTopicsByProjects} in bounded batches, one transaction each. A plain read: a delete that joined
     * {@code projects} would lock the rows it read there, and every topics row it scanned. A deleted project id never
     * comes back, so the read needs no lock.
     */
    @SqlQuery("""
            SELECT DISTINCT t.workspace_id, t.project_id FROM topics t
            LEFT JOIN projects p ON p.id = t.project_id AND p.workspace_id = t.workspace_id
            WHERE t.workspace_id IN (<workspaceIds>) AND p.id IS NULL
            """)
    List<TopicProjectKey> findTopicProjectsOfMissingProjects(@BindList("workspaceIds") Collection<String> workspaceIds);
}

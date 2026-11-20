package com.comet.opik.domain.topics;

import com.comet.opik.infrastructure.db.UUIDArgumentFactory;
import lombok.NonNull;
import org.jdbi.v3.sqlobject.config.RegisterArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindList;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * The night fields of a project's {@code topic_projects} row, and the counts the nightly gauges read from the rows. The
 * night pass of the planner ({@link TopicsNightPass}) writes them, and only rows that exist: a project with a topic
 * list always has one. Each night logs its decisions and
 * emits metrics; the row keeps no history.
 */
@RegisterArgumentFactory(UUIDArgumentFactory.class)
@RegisterConstructorMapper(TopicProjectNightDAO.WorkspaceCount.class)
@RegisterConstructorMapper(TopicProjectKey.class)
public interface TopicProjectNightDAO {

    /** The night looked at the projects: updated or skipped them, for any reason. */
    @SqlUpdate("""
            UPDATE topic_projects SET last_night = :night, not_reached_nights = 0
            WHERE workspace_id = :workspaceId AND project_id IN (<projectIds>)
            """)
    int markLooked(@Bind("workspaceId") String workspaceId, @BindList("projectIds") Collection<UUID> projectIds,
            @Bind("night") LocalDate night);

    /**
     * The streak a night that did not reach the project gives it: the nights since the last one that looked at it, or
     * since its last save when none did, at least 1. It comes from the dates alone, so writing it again for the same
     * night changes nothing.
     */
    String NOT_REACHED_STREAK = """
            GREATEST(1, DATEDIFF(:night, GREATEST(COALESCE(last_night, DATE(last_success_at), :night),
                COALESCE(DATE(last_success_at), last_night, :night))))""";

    /**
     * The projects of {@code workspaceIds} with a list that the night did not look at and whose streak does not count
     * the night yet: the ones {@link #markNotReached} writes.
     */
    @SqlQuery("SELECT workspace_id, project_id FROM topic_projects WHERE workspace_id IN (<workspaceIds>) "
            + "AND list_run_id IS NOT NULL AND (last_night IS NULL OR last_night < :night) "
            + "AND not_reached_nights < " + NOT_REACHED_STREAK)
    List<TopicProjectKey> findNotReached(@BindList("workspaceIds") Collection<String> workspaceIds,
            @Bind("night") LocalDate night);

    /** The night's budget ended before the projects: their streak counts it, once (see {@link #NOT_REACHED_STREAK}). */
    @SqlUpdate("UPDATE topic_projects SET not_reached_nights = " + NOT_REACHED_STREAK
            + " WHERE workspace_id = :workspaceId AND project_id IN (<projectIds>) "
            + "AND (last_night IS NULL OR last_night < :night)")
    int markNotReached(@Bind("workspaceId") String workspaceId, @BindList("projectIds") Collection<UUID> projectIds,
            @Bind("night") LocalDate night);

    /** The latest night that looked at any project of {@code workspaceIds}; null before the first. */
    @SqlQuery("SELECT MAX(last_night) FROM topic_projects WHERE workspace_id IN (<workspaceIds>)")
    LocalDate lastNight(@BindList("workspaceIds") Collection<String> workspaceIds);

    /** Per workspace, the projects with a list whose last {@code failures} attempts or more failed in a row. */
    @SqlQuery("""
            SELECT workspace_id, COUNT(*) AS projects FROM topic_projects
            WHERE workspace_id IN (<workspaceIds>) AND list_run_id IS NOT NULL AND failed_runs >= :failures
            GROUP BY workspace_id
            """)
    List<WorkspaceCount> countStale(@BindList("workspaceIds") Collection<String> workspaceIds,
            @Bind("failures") int failures);

    /** Per workspace, the projects the last {@code nights} nights or more in a row did not reach. */
    @SqlQuery("""
            SELECT workspace_id, COUNT(*) AS projects FROM topic_projects
            WHERE workspace_id IN (<workspaceIds>) AND not_reached_nights >= :nights
            GROUP BY workspace_id
            """)
    List<WorkspaceCount> countStarved(@BindList("workspaceIds") Collection<String> workspaceIds,
            @Bind("nights") int nights);

    record WorkspaceCount(@NonNull String workspaceId, long projects) {
    }
}

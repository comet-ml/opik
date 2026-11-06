package com.comet.opik.domain.topics;

import com.comet.opik.infrastructure.db.AbstractEnumColumnMapper;
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
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

/**
 * {@code topic_nights} and {@code topic_night_projects}: what each night decided, written as the night goes, and the
 * reads behind the state gauges ({@link TopicsNightlyGauges}). A night's row is {@code running} from its start and
 * {@code finished} or {@code failed} at its end. The gauge reads count a workspace only while it is still enabled in
 * {@code ai_label_workspaces}; the gauges keep only the workspaces still in {@code topics.enabledWorkspaces}.
 */
@RegisterArgumentFactory(UUIDArgumentFactory.class)
@RegisterArgumentFactory(TopicNightDAO.OutcomeMapper.class)
@RegisterColumnMapper(TopicNightDAO.OutcomeMapper.class)
@RegisterConstructorMapper(TopicNight.class)
@RegisterConstructorMapper(TopicNightProject.class)
@RegisterConstructorMapper(TopicNightDAO.ProjectCount.class)
@RegisterConstructorMapper(TopicNightDAO.RunCount.class)
@RegisterConstructorMapper(TopicNightDAO.WorkspaceCount.class)
public interface TopicNightDAO {

    /** Keeps the rows of workspaces still opted in to ai-labels; {@code np} is the night project. */
    String ELIGIBLE = """
            JOIN ai_label_workspaces a ON a.workspace_id = np.workspace_id AND a.enabled = TRUE
            """;

    /** A night's projects per workspace, decision and skip reason ({@code reason} null for no skip). */
    record ProjectCount(@NonNull String workspaceId, @NonNull String decision, @Nullable String reason,
            long projects) {
    }

    /** A night's runs per workspace and status. */
    record RunCount(@NonNull String workspaceId, @NonNull String runStatus, long runs) {
    }

    record WorkspaceCount(@NonNull String workspaceId, long projects) {
    }

    /** The night's row as it starts. A night run again for the same date replaces the earlier row. */
    @SqlUpdate("""
            INSERT INTO topic_nights (night, started_at, outcome) VALUES (:night, :startedAt, 'running')
            ON DUPLICATE KEY UPDATE started_at = :startedAt, finished_at = NULL, outcome = 'running',
                eligible_workspaces = 0, count_failures = 0, projects = NULL
            """)
    void start(@Bind("night") LocalDate night, @Bind("startedAt") Instant startedAt);

    @SqlUpdate("DELETE FROM topic_night_projects WHERE night = :night")
    int deleteProjects(@Bind("night") LocalDate night);

    /** The night's row as it ends; inserted when its start's write failed. */
    @SqlUpdate("""
            INSERT INTO topic_nights (night, started_at, finished_at, outcome, eligible_workspaces, count_failures,
                projects)
            VALUES (:night, :startedAt, :finishedAt, :outcome, :eligibleWorkspaces, :countFailures, :projects)
            ON DUPLICATE KEY UPDATE finished_at = :finishedAt, outcome = :outcome,
                eligible_workspaces = :eligibleWorkspaces, count_failures = :countFailures, projects = :projects
            """)
    void end(@BindMethods TopicNight night);

    @SqlBatch("""
            INSERT INTO topic_night_projects (id, night, workspace_id, project_id, vectors, decision, reason, run_id)
            VALUES (:id, :night, :workspaceId, :projectId, :vectors, :decision, :reason, :runId)
            """)
    void insertProjects(@BindMethods List<TopicNightProject> projects);

    /** Nights a pod that died left {@code running}: failed. */
    @SqlUpdate("""
            UPDATE topic_nights SET outcome = 'failed', finished_at = :now
            WHERE outcome = 'running' AND started_at < :startedBefore
            """)
    int failAbandoned(@Bind("startedBefore") Instant startedBefore, @Bind("now") Instant now);

    @SqlUpdate("DELETE FROM topic_nights WHERE night < :before")
    int deleteNightsBefore(@Bind("before") LocalDate before);

    @SqlUpdate("DELETE FROM topic_night_projects WHERE night < :before")
    int deleteProjectsBefore(@Bind("before") LocalDate before);

    @SqlQuery("SELECT * FROM topic_nights WHERE night = :night")
    TopicNight findByNight(@Bind("night") LocalDate night);

    @SqlQuery("SELECT * FROM topic_night_projects WHERE night = :night ORDER BY workspace_id, project_id")
    List<TopicNightProject> findProjects(@Bind("night") LocalDate night);

    /** When the latest night started; null before the first night. */
    @SqlQuery("SELECT MAX(started_at) FROM topic_nights")
    Instant lastStartedAt();

    /** When the oldest night still {@code running} started; null when none runs. */
    @SqlQuery("SELECT MIN(started_at) FROM topic_nights WHERE outcome = 'running'")
    Instant runningSince();

    /** The latest nights that ended, by start, newest first; of two that started at once, the later night first. */
    @SqlQuery("SELECT * FROM topic_nights WHERE outcome <> 'running' ORDER BY started_at DESC, night DESC "
            + "LIMIT :limit")
    List<TopicNight> findLatestEnded(@Bind("limit") int limit);

    @SqlQuery("SELECT np.workspace_id, np.decision, np.reason, COUNT(*) AS projects "
            + "FROM topic_night_projects np " + ELIGIBLE
            + "WHERE np.night = :night GROUP BY np.workspace_id, np.decision, np.reason")
    List<ProjectCount> countProjects(@Bind("night") LocalDate night);

    /**
     * The night's runs per status: the run's {@code topic_runs} status, or {@code failed} for a run that could not
     * start (a run decision without a run). A run whose row is gone (its project was deleted) is left out.
     */
    @SqlQuery("SELECT np.workspace_id, CASE WHEN np.run_id IS NULL THEN 'failed' ELSE r.status END AS run_status, "
            + "COUNT(*) AS runs "
            + "FROM topic_night_projects np LEFT JOIN topic_runs r ON r.run_id = np.run_id " + ELIGIBLE
            + "WHERE np.night = :night AND np.decision NOT IN ('skip', 'not_reached', 'count_failed') "
            + "AND (np.run_id IS NULL OR r.run_id IS NOT NULL) "
            + "GROUP BY np.workspace_id, run_status")
    List<RunCount> countRuns(@Bind("night") LocalDate night);

    /**
     * Projects that were expected on every one of {@code nights} and got no saved run since {@code from}, the earliest
     * night's start: a run by hand counts. Expected is any decision but a skip as {@code small}, {@code deleted} or
     * {@code already_saved}: quiet projects, gone ones and ones with a current list are left out. So are projects
     * deleted since.
     */
    @SqlQuery("SELECT np.workspace_id, COUNT(*) AS projects FROM ("
            + "SELECT workspace_id, project_id FROM topic_night_projects "
            + "WHERE night IN (<nights>) AND project_id IS NOT NULL AND decision <> 'count_failed' "
            + "AND NOT (decision = 'skip' AND reason IN ('small', 'deleted', 'already_saved')) "
            + "GROUP BY workspace_id, project_id HAVING COUNT(DISTINCT night) = :nightCount"
            + ") np " + ELIGIBLE
            + "JOIN projects p ON p.id = np.project_id AND p.workspace_id = np.workspace_id "
            + "WHERE NOT EXISTS (SELECT 1 FROM topic_runs r "
            + "WHERE r.project_id = np.project_id AND r.status = 'saved' AND r.started_at >= :from) "
            + "GROUP BY np.workspace_id")
    List<WorkspaceCount> countStale(@BindList("nights") Collection<LocalDate> nights,
            @Bind("nightCount") int nightCount, @Bind("from") Instant from);

    /** Projects {@code not_reached} on every one of {@code nights}, still existing. */
    @SqlQuery("SELECT np.workspace_id, COUNT(*) AS projects FROM ("
            + "SELECT workspace_id, project_id FROM topic_night_projects "
            + "WHERE night IN (<nights>) AND decision = 'not_reached' "
            + "GROUP BY workspace_id, project_id HAVING COUNT(DISTINCT night) = :nightCount"
            + ") np " + ELIGIBLE
            + "JOIN projects p ON p.id = np.project_id AND p.workspace_id = np.workspace_id "
            + "GROUP BY np.workspace_id")
    List<WorkspaceCount> countStarved(@BindList("nights") Collection<LocalDate> nights,
            @Bind("nightCount") int nightCount);

    class OutcomeMapper extends AbstractEnumColumnMapper<TopicNight.Outcome> {
        public OutcomeMapper() {
            super(value -> Arrays.stream(TopicNight.Outcome.values())
                    .filter(outcome -> outcome.getValue().equals(value))
                    .findFirst()
                    .orElseThrow(
                            () -> new IllegalArgumentException("Unknown topic night outcome '%s'".formatted(value))),
                    "topic_night_outcome");
        }
    }
}

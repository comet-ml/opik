package com.comet.opik.domain.ailabels;

import com.comet.opik.infrastructure.db.AbstractEnumColumnMapper;
import org.jdbi.v3.sqlobject.config.RegisterArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterColumnMapper;
import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindMethods;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

/**
 * The opik-inference jobs ai-labels has submitted. Timestamps the poller compares are taken from MySQL's clock, so pods
 * with skewed clocks agree on which jobs are due.
 */
@RegisterArgumentFactory(AiLabelJobDAO.EndpointMapper.class)
@RegisterArgumentFactory(AiLabelJobDAO.StatusMapper.class)
@RegisterColumnMapper(AiLabelJobDAO.EndpointMapper.class)
@RegisterColumnMapper(AiLabelJobDAO.StatusMapper.class)
@RegisterConstructorMapper(AiLabelJob.class)
public interface AiLabelJobDAO {

    @SqlUpdate("""
            INSERT INTO ai_label_jobs (job_id, workspace_id, endpoint, cache, item_count, engine_calls, status,
                submitted_at, last_polled_at, terminal_at)
            VALUES (:jobId, :workspaceId, :endpoint, :cache, :itemCount, :engineCalls, :status,
                :submittedAt, :lastPolledAt, :terminalAt)
            """)
    void insert(@BindMethods AiLabelJob job);

    /** Submitted jobs not polled within {@code pollInterval}, never-polled and least recently polled first. */
    default List<AiLabelJob> due(Duration pollInterval, int limit) {
        return due(pollInterval.toNanos() / 1_000, limit);
    }

    @SqlQuery("""
            SELECT * FROM ai_label_jobs
            WHERE status = 'submitted'
            AND (last_polled_at IS NULL
                OR last_polled_at <= TIMESTAMPADD(MICROSECOND, -:pollIntervalMicros, CURRENT_TIMESTAMP(6)))
            ORDER BY last_polled_at, submitted_at
            LIMIT :limit
            """)
    List<AiLabelJob> due(@Bind("pollIntervalMicros") long pollIntervalMicros, @Bind("limit") int limit);

    @SqlUpdate("UPDATE ai_label_jobs SET last_polled_at = CURRENT_TIMESTAMP(6) WHERE job_id = :jobId")
    int markPolled(@Bind("jobId") String jobId);

    @SqlUpdate("UPDATE ai_label_jobs SET status = 'loaded', terminal_at = :terminalAt WHERE job_id = :jobId")
    int markLoaded(@Bind("jobId") String jobId, @Bind("terminalAt") Instant terminalAt);

    @SqlQuery("SELECT COALESCE(SUM(engine_calls), 0) FROM ai_label_jobs WHERE status = 'submitted'")
    long inFlightEngineCalls();

    @SqlUpdate("DELETE FROM ai_label_jobs WHERE status = 'loaded' AND terminal_at < :before")
    int deleteLoadedBefore(@Bind("before") Instant before);

    class EndpointMapper extends AbstractEnumColumnMapper<AiLabelEndpoint> {
        public EndpointMapper() {
            super(value -> Arrays.stream(AiLabelEndpoint.values())
                    .filter(endpoint -> endpoint.getValue().equals(value))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown ai-label endpoint '%s'".formatted(value))),
                    "ai_label_endpoint");
        }
    }

    class StatusMapper extends AbstractEnumColumnMapper<AiLabelJob.Status> {
        public StatusMapper() {
            super(value -> Arrays.stream(AiLabelJob.Status.values())
                    .filter(status -> status.getValue().equals(value))
                    .findFirst()
                    .orElseThrow(
                            () -> new IllegalArgumentException("Unknown ai-label job status '%s'".formatted(value))),
                    "ai_label_job_status");
        }
    }
}

package com.comet.opik.domain.ailabels;

import com.comet.opik.infrastructure.db.AbstractEnumColumnMapper;
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

import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

/**
 * The opik-inference jobs ai-labels has submitted, and the (trace, task) items of those still in flight. A job and its
 * items are written in one transaction, and its items are deleted in the one that marks it loaded. An empty id list
 * matches nothing.
 */
@RegisterArgumentFactory(AiLabelJobDAO.EndpointMapper.class)
@RegisterArgumentFactory(AiLabelJobDAO.StatusMapper.class)
@RegisterArgumentFactory(UUIDArgumentFactory.class)
@RegisterColumnMapper(AiLabelJobDAO.EndpointMapper.class)
@RegisterColumnMapper(AiLabelJobDAO.StatusMapper.class)
@RegisterConstructorMapper(AiLabelJob.class)
@RegisterConstructorMapper(AiLabelJobItem.class)
public interface AiLabelJobDAO {

    @SqlUpdate("""
            INSERT INTO ai_label_jobs (job_id, workspace_id, endpoint, cache, item_count, bytes, status, submitted_at,
                terminal_at)
            VALUES (:jobId, :workspaceId, :endpoint, :cache, :itemCount, :bytes, :status, :submittedAt, :terminalAt)
            """)
    void insert(@BindMethods AiLabelJob job);

    @SqlBatch("""
            INSERT INTO ai_label_job_items (job_id, workspace_id, project_id, trace_id, trace_created_at, task,
                task_version, renderer_version, compaction_level, attempt)
            VALUES (:jobId, :workspaceId, :projectId, :traceId, :traceCreatedAt, :task, :taskVersion, :rendererVersion,
                :compactionLevel, :attempt)
            """)
    void insertItems(@BindMethods List<AiLabelJobItem> items);

    @SqlQuery("SELECT * FROM ai_label_jobs WHERE status = 'submitted' ORDER BY submitted_at")
    List<AiLabelJob> submitted();

    @SqlQuery("SELECT * FROM ai_label_job_items WHERE job_id IN (<jobIds>)")
    List<AiLabelJobItem> findItems(
            @BindList(value = "jobIds", onEmpty = BindList.EmptyHandling.NULL_STRING) Collection<String> jobIds);

    /** The traces with a task in flight in any of the workspaces: the sweep leaves them out. */
    @SqlQuery("SELECT DISTINCT trace_id FROM ai_label_job_items WHERE workspace_id IN (<workspaceIds>)")
    List<String> inFlightTraceIds(
            @BindList(value = "workspaceIds", onEmpty = BindList.EmptyHandling.NULL_STRING) Collection<String> workspaceIds);

    @SqlUpdate("UPDATE ai_label_jobs SET status = 'loaded', terminal_at = :terminalAt WHERE job_id IN (<jobIds>)")
    int markLoaded(@BindList(value = "jobIds", onEmpty = BindList.EmptyHandling.NULL_STRING) Collection<String> jobIds,
            @Bind("terminalAt") Instant terminalAt);

    @SqlUpdate("DELETE FROM ai_label_job_items WHERE job_id IN (<jobIds>)")
    int deleteItems(
            @BindList(value = "jobIds", onEmpty = BindList.EmptyHandling.NULL_STRING) Collection<String> jobIds);

    @SqlQuery("SELECT COALESCE(SUM(bytes), 0) FROM ai_label_jobs WHERE status = 'submitted'")
    long inFlightBytes();

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

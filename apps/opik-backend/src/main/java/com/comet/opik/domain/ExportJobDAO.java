package com.comet.opik.domain;

import com.comet.opik.api.ExportJob;
import com.comet.opik.api.ExportStatus;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.db.ExportParamsArgumentFactory;
import com.comet.opik.infrastructure.db.UUIDArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterColumnMapper;
import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindList;
import org.jdbi.v3.sqlobject.customizer.BindMethods;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@RegisterArgumentFactory(UUIDArgumentFactory.class)
@RegisterArgumentFactory(ExportParamsArgumentFactory.class)
@RegisterColumnMapper(ExportParamsArgumentFactory.class)
@RegisterConstructorMapper(ExportJob.class)
public interface ExportJobDAO {

    @SqlUpdate("""
            INSERT INTO export_jobs (
                id,
                workspace_id,
                export_type,
                params,
                params_hash,
                resource_name,
                status,
                file_path,
                error_message,
                created_at,
                last_updated_at,
                expires_at,
                created_by,
                last_updated_by
            ) VALUES (
                :job.id,
                :workspaceId,
                :job.exportType,
                :job.params,
                :job.paramsHash,
                :job.resourceName,
                :job.status,
                :job.filePath,
                :job.errorMessage,
                :job.createdAt,
                :job.lastUpdatedAt,
                :job.expiresAt,
                :job.createdBy,
                :job.lastUpdatedBy
            )
            """)
    void save(@BindMethods("job") ExportJob job, @Bind("workspaceId") String workspaceId);

    /**
     * Marks a PENDING dataset export job as PROCESSING.
     * Sets status and last_updated_by.
     * Only allows transition from PENDING to PROCESSING.
     *
     * @param workspaceId The workspace ID for security
     * @param id The job ID to update
     * @param lastUpdatedBy The user who updated the job
     * @return The number of rows updated (0 if job not found or doesn't belong to workspace or not in PENDING state)
     */
    @SqlUpdate("""
            UPDATE export_jobs
            SET status = 'PROCESSING',
                last_updated_by = :lastUpdatedBy
            WHERE id = :id
                AND workspace_id = :workspaceId
                AND status = 'PENDING'
            """)
    int markPendingJobAsProcessing(@Bind("workspaceId") String workspaceId,
            @Bind("id") UUID id,
            @Bind("lastUpdatedBy") String lastUpdatedBy);

    /**
     * Updates a dataset export job when it completes successfully.
     * Sets status, file_path, expires_at, last_updated_by, and clears error_message and viewed_at.
     * Only allows transition from PROCESSING to COMPLETED.
     *
     * @param workspaceId The workspace ID for security
     * @param id The job ID to update
     * @param status The new status (typically COMPLETED)
     * @param filePath The path to the exported file
     * @param expiresAt The expiration timestamp
     * @param lastUpdatedBy The user who updated the job
     * @return The number of rows updated (0 if job not found or doesn't belong to workspace or invalid state transition)
     */
    @SqlUpdate("""
            UPDATE export_jobs
            SET status = :status,
                file_path = :filePath,
                expires_at = :expiresAt,
                last_updated_by = :lastUpdatedBy,
                error_message = NULL,
                viewed_at = NULL
            WHERE id = :id
                AND workspace_id = :workspaceId
                AND status = 'PROCESSING'
            """)
    int updateToCompleted(@Bind("workspaceId") String workspaceId,
            @Bind("id") UUID id,
            @Bind("status") ExportStatus status,
            @Bind("filePath") String filePath,
            @Bind("expiresAt") java.time.Instant expiresAt,
            @Bind("lastUpdatedBy") String lastUpdatedBy);

    /**
     * Updates a dataset export job when it fails.
     * Sets status, error_message, last_updated_by.
     * Does NOT clear file_path - files must be cleaned up by cleanup job.
     * Allows transition from PENDING or PROCESSING to FAILED.
     *
     * @param workspaceId The workspace ID for security
     * @param id The job ID to update
     * @param errorMessage The error message describing the failure
     * @param lastUpdatedBy The user who updated the job
     * @return The number of rows updated (0 if job not found or doesn't belong to workspace or invalid state transition)
     */
    @SqlUpdate("""
            UPDATE export_jobs
            SET status = 'FAILED',
                error_message = :errorMessage,
                last_updated_by = :lastUpdatedBy
            WHERE id = :id
                AND workspace_id = :workspaceId
                AND status IN ('PENDING', 'PROCESSING')
            """)
    int updateToFailed(@Bind("workspaceId") String workspaceId,
            @Bind("id") UUID id,
            @Bind("errorMessage") String errorMessage,
            @Bind("lastUpdatedBy") String lastUpdatedBy);

    @SqlQuery("""
            SELECT
                j.id,
                j.params,
                j.resource_name,
                j.status,
                j.file_path,
                j.error_message,
                j.created_at,
                j.last_updated_at,
                j.expires_at,
                j.viewed_at,
                j.created_by,
                j.last_updated_by
            FROM export_jobs j
            WHERE j.id = :id
            AND j.workspace_id = :workspaceId
            AND (j.created_by = :userName OR :userName = '""" + RequestContext.SYSTEM_USER + "')")
    Optional<ExportJob> findById(@Bind("workspaceId") String workspaceId, @Bind("id") UUID id,
            @Bind("userName") String userName);

    @SqlQuery("""
            SELECT
                j.id,
                j.params,
                j.resource_name,
                j.status,
                j.file_path,
                j.error_message,
                j.created_at,
                j.last_updated_at,
                j.expires_at,
                j.viewed_at,
                j.created_by,
                j.last_updated_by
            FROM export_jobs j
            WHERE j.workspace_id = :workspaceId
                AND j.export_type = :exportType
                AND j.params_hash = :paramsHash
                AND j.created_by = :userName
                AND j.status IN (<statuses>)
            """)
    List<ExportJob> findInProgressByParams(
            @Bind("workspaceId") String workspaceId,
            @Bind("exportType") String exportType,
            @Bind("paramsHash") String paramsHash,
            @Bind("userName") String userName,
            @BindList("statuses") Set<ExportStatus> statuses);

    /**
     * Finds the caller's own export jobs in a workspace.
     * Returns all statuses - the cleanup job handles removing old jobs, and the frontend checks viewed_at to
     * decide whether to show error toasts for failed jobs.
     *
     * @param workspaceId The workspace ID
     * @param userName    The caller; jobs started by other members of the workspace are not returned
     * @return List of the caller's export jobs
     */
    @SqlQuery("""
            SELECT
                j.id,
                j.params,
                j.resource_name,
                j.status,
                j.file_path,
                j.error_message,
                j.created_at,
                j.last_updated_at,
                j.expires_at,
                j.viewed_at,
                j.created_by,
                j.last_updated_by
            FROM export_jobs j
            WHERE j.workspace_id = :workspaceId
            AND j.created_by = :userName
            ORDER BY j.id DESC
            """)
    List<ExportJob> findByWorkspace(@Bind("workspaceId") String workspaceId,
            @Bind("userName") String userName);

    @SqlUpdate("""
            UPDATE export_jobs
            SET viewed_at = :viewedAt,
                last_updated_by = :lastUpdatedBy
            WHERE id = :id
                AND workspace_id = :workspaceId
                AND viewed_at IS NULL
            """)
    int updateViewedAt(@Bind("workspaceId") String workspaceId,
            @Bind("id") UUID id,
            @Bind("viewedAt") Instant viewedAt,
            @Bind("lastUpdatedBy") String lastUpdatedBy);

    /**
     * Finds expired completed export jobs across all workspaces for cleanup.
     *
     * <p><strong>Security Warning:</strong> This query operates across ALL workspaces without filtering.
     * It should ONLY be called by system-level cleanup jobs. The userName parameter MUST be
     * {@link com.comet.opik.infrastructure.auth.RequestContext#SYSTEM_USER}.</p>
     *
     * @param userName  The name of the user making the request (must be SYSTEM_USER)
     * @param now       Current timestamp for expiration comparison
     * @param limit     Maximum number of jobs to return
     * @return List of expired completed jobs across all workspaces, or empty list if userName is not SYSTEM_USER
     */
    @SqlQuery("""
            SELECT
                id,
                workspace_id,
                params,
                resource_name,
                status,
                file_path,
                error_message,
                created_at,
                last_updated_at,
                expires_at,
                viewed_at,
                created_by,
                last_updated_by
            FROM export_jobs
            WHERE expires_at < :now
                AND status = 'COMPLETED'
                AND :userName = '""" + RequestContext.SYSTEM_USER + "'"
            + """
                        ORDER BY expires_at ASC
                        LIMIT :limit
                    """)
    List<ExportJob> findExpiredCompletedJobs(@Bind("userName") String userName,
            @Bind("now") Instant now,
            @Bind("limit") int limit);

    /**
     * Finds viewed failed export jobs across all workspaces for cleanup.
     *
     * <p><strong>Security Warning:</strong> This query operates across ALL workspaces without filtering.
     * It should ONLY be called by system-level cleanup jobs. The userName parameter MUST be
     * {@link com.comet.opik.infrastructure.auth.RequestContext#SYSTEM_USER}.</p>
     *
     * @param userName  The name of the user making the request (must be SYSTEM_USER)
     * @param limit     Maximum number of jobs to return
     * @return List of viewed failed jobs across all workspaces, or empty list if userName is not SYSTEM_USER
     */
    @SqlQuery("""
            SELECT
                id,
                workspace_id,
                params,
                resource_name,
                status,
                file_path,
                error_message,
                created_at,
                last_updated_at,
                expires_at,
                viewed_at,
                created_by,
                last_updated_by
            FROM export_jobs
            WHERE status = 'FAILED'
                AND viewed_at IS NOT NULL
                AND :userName = '""" + RequestContext.SYSTEM_USER + "'"
            + """
                        ORDER BY viewed_at ASC
                        LIMIT :limit
                    """)
    List<ExportJob> findViewedFailedJobs(@Bind("userName") String userName, @Bind("limit") int limit);

    /**
     * Deletes export jobs by their IDs across all workspaces.
     * Used by cleanup job to delete both expired completed jobs and viewed failed jobs.
     *
     * <p><strong>Security Warning:</strong> This operation affects ALL workspaces without filtering.
     * It should ONLY be called by system-level cleanup jobs. The userName parameter MUST be
     * {@link com.comet.opik.infrastructure.auth.RequestContext#SYSTEM_USER}.</p>
     *
     * @param userName  The name of the user making the request (must be SYSTEM_USER)
     * @param ids       Set of job IDs to delete
     * @return Number of deleted records, or 0 if userName is not SYSTEM_USER
     */
    @SqlUpdate("""
            DELETE FROM export_jobs
            WHERE id IN (<ids>)
            AND :userName = '""" + RequestContext.SYSTEM_USER + "'")
    int deleteJobsByIds(@Bind("userName") String userName, @BindList("ids") Set<UUID> ids);

    @SqlUpdate("""
            UPDATE export_jobs
            SET viewed_at = :viewedAt
            WHERE id = :id
                AND workspace_id = :workspaceId
                AND viewed_at IS NULL
            """)
    int updateViewedAt(@Bind("workspaceId") String workspaceId,
            @Bind("id") UUID id,
            @Bind("viewedAt") Instant viewedAt);

    @SqlUpdate("DELETE FROM export_jobs WHERE workspace_id = :workspaceId AND id IN (<ids>)")
    int deleteByIds(@Bind("workspaceId") String workspaceId, @BindList("ids") Set<UUID> ids);
}

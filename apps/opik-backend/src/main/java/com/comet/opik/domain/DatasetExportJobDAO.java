package com.comet.opik.domain;

import com.comet.opik.api.DatasetExportJob;
import com.comet.opik.api.DatasetExportStatus;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.db.UUIDArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterArgumentFactory;
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
@RegisterConstructorMapper(DatasetExportJob.class)
public interface DatasetExportJobDAO {

    @SqlUpdate("""
            INSERT INTO dataset_export_jobs (
                id,
                workspace_id,
                dataset_id,
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
                :job.datasetId,
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
    void save(@BindMethods("job") DatasetExportJob job, @Bind("workspaceId") String workspaceId);

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
            UPDATE dataset_export_jobs
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
            UPDATE dataset_export_jobs
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
            @Bind("status") DatasetExportStatus status,
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
            UPDATE dataset_export_jobs
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
                id,
                dataset_id,
                status,
                file_path,
                error_message,
                created_at,
                last_updated_at,
                expires_at,
                viewed_at,
                created_by,
                last_updated_by
            FROM dataset_export_jobs
            WHERE id = :id
            AND workspace_id = :workspaceId
            """)
    Optional<DatasetExportJob> findById(@Bind("workspaceId") String workspaceId, @Bind("id") UUID id);

    @SqlQuery("""
            SELECT
                id,
                dataset_id,
                status,
                file_path,
                error_message,
                created_at,
                last_updated_at,
                expires_at,
                viewed_at,
                created_by,
                last_updated_by
            FROM dataset_export_jobs
            WHERE workspace_id = :workspaceId
                AND dataset_id = :datasetId
                AND status IN (<statuses>)
            ORDER BY created_at DESC
            """)
    List<DatasetExportJob> findInProgressByDataset(
            @Bind("workspaceId") String workspaceId,
            @Bind("datasetId") UUID datasetId,
            @BindList("statuses") Set<DatasetExportStatus> statuses);

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
                dataset_id,
                status,
                file_path,
                error_message,
                created_at,
                last_updated_at,
                expires_at,
                viewed_at,
                created_by,
                last_updated_by
            FROM dataset_export_jobs
            WHERE expires_at < :now
                AND status = 'COMPLETED'
                AND :userName = '""" + RequestContext.SYSTEM_USER + "'"
            + """
                        ORDER BY expires_at ASC
                        LIMIT :limit
                    """)
    List<DatasetExportJob> findExpiredCompletedJobs(@Bind("userName") String userName,
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
                dataset_id,
                status,
                file_path,
                error_message,
                created_at,
                last_updated_at,
                expires_at,
                viewed_at,
                created_by,
                last_updated_by
            FROM dataset_export_jobs
            WHERE status = 'FAILED'
                AND viewed_at IS NOT NULL
                AND :userName = '""" + RequestContext.SYSTEM_USER + "'"
            + """
                        ORDER BY viewed_at ASC
                        LIMIT :limit
                    """)
    List<DatasetExportJob> findViewedFailedJobs(@Bind("userName") String userName, @Bind("limit") int limit);

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
            DELETE FROM dataset_export_jobs
            WHERE id IN (<ids>)
            AND :userName = '""" + RequestContext.SYSTEM_USER + "'")
    int deleteJobsByIds(@Bind("userName") String userName, @BindList("ids") Set<UUID> ids);

    @SqlUpdate("""
            UPDATE dataset_export_jobs
            SET viewed_at = :viewedAt
            WHERE id = :id
                AND workspace_id = :workspaceId
                AND viewed_at IS NULL
            """)
    int updateViewedAt(@Bind("workspaceId") String workspaceId,
            @Bind("id") UUID id,
            @Bind("viewedAt") Instant viewedAt);

    @SqlUpdate("DELETE FROM dataset_export_jobs WHERE workspace_id = :workspaceId AND id IN (<ids>)")
    int deleteByIds(@Bind("workspaceId") String workspaceId, @BindList("ids") Set<UUID> ids);
}

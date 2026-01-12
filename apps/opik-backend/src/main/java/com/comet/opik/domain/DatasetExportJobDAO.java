package com.comet.opik.domain;

import com.comet.opik.api.DatasetExportJob;
import com.comet.opik.api.DatasetExportStatus;
import com.comet.opik.infrastructure.db.UUIDArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindList;
import org.jdbi.v3.sqlobject.customizer.BindMethods;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

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
     * Updates a dataset export job when it completes successfully.
     * Sets status, file_path, last_updated_by, and clears error_message.
     *
     * @param workspaceId The workspace ID for security
     * @param id The job ID to update
     * @param status The new status (typically COMPLETED)
     * @param filePath The path to the exported file
     * @param lastUpdatedBy The user who updated the job
     * @return The number of rows updated (0 or 1)
     */
    @SqlUpdate("""
            UPDATE dataset_export_jobs
            SET status = :status,
                file_path = :filePath,
                last_updated_by = :lastUpdatedBy,
                error_message = NULL
            WHERE id = :id
                AND workspace_id = :workspaceId
            """)
    int updateToCompleted(@Bind("workspaceId") String workspaceId,
            @Bind("id") UUID id,
            @Bind("status") DatasetExportStatus status,
            @Bind("filePath") String filePath,
            @Bind("lastUpdatedBy") String lastUpdatedBy);

    /**
     * Updates a dataset export job when it fails.
     * Sets status, error_message, last_updated_by, and clears file_path.
     *
     * @param workspaceId The workspace ID for security
     * @param id The job ID to update
     * @param status The new status (typically FAILED)
     * @param errorMessage The error message describing the failure
     * @param lastUpdatedBy The user who updated the job
     * @return The number of rows updated (0 or 1)
     */
    @SqlUpdate("""
            UPDATE dataset_export_jobs
            SET status = :status,
                file_path = NULL,
                error_message = :errorMessage,
                last_updated_by = :lastUpdatedBy
            WHERE id = :id
                AND workspace_id = :workspaceId
            """)
    int updateToFailed(@Bind("workspaceId") String workspaceId,
            @Bind("id") UUID id,
            @Bind("status") DatasetExportStatus status,
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

    @SqlUpdate("DELETE FROM dataset_export_jobs WHERE workspace_id = :workspaceId AND id IN (<ids>)")
    int deleteByIds(@Bind("workspaceId") String workspaceId, @BindList("ids") Set<UUID> ids);
}

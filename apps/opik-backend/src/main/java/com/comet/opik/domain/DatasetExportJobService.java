package com.comet.opik.domain;

import com.comet.opik.api.DatasetExportJob;
import com.comet.opik.api.DatasetExportStatus;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.google.inject.ImplementedBy;
import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.NotFoundException;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;

@ImplementedBy(DatasetExportJobServiceImpl.class)
public interface DatasetExportJobService {

    /**
     * Creates a new export job for the specified dataset.
     *
     * @param datasetId The dataset ID to export
     * @param ttl       Time-to-live duration for the export file
     * @param versionId Optional version ID. If null, uses legacy table. If provided, uses versioned table.
     * @return Mono emitting the created export job
     */
    Mono<DatasetExportJob> createJob(UUID datasetId, Duration ttl, @Nullable UUID versionId);

    /**
     * Finds all in-progress (PENDING or PROCESSING) export jobs for a dataset.
     * Used to check for existing jobs before creating a new one.
     *
     * @param datasetId The dataset ID to check
     * @return Mono emitting list of in-progress export jobs
     */
    Mono<List<DatasetExportJob>> findInProgressJobs(UUID datasetId);

    /**
     * Finds all export jobs for the current workspace.
     * Returns all jobs regardless of status - the cleanup job handles removing old jobs.
     * This is used to restore the export panel state after page refresh.
     *
     * @return Mono emitting list of all export jobs for the workspace
     */
    Mono<List<DatasetExportJob>> findAllJobs();

    /**
     * Retrieves an export job by its ID.
     *
     * @param jobId The job ID to retrieve
     * @return Mono emitting the export job
     * @throws NotFoundException if job doesn't exist or doesn't belong to the current workspace
     */
    Mono<DatasetExportJob> getJob(UUID jobId);

    /**
     * Marks a job as viewed by setting the viewed_at timestamp.
     * This is used to track that a user has seen a failed job's error message.
     *
     * @param jobId The job ID to mark as viewed
     * @return Mono completing when the job is marked as viewed
     */
    Mono<Void> markJobAsViewed(UUID jobId);

    /**
     * Updates the job status from PENDING to PROCESSING.
     * Called when the export worker starts processing the job.
     *
     * @param jobId The job ID to update
     * @return Mono completing when the status is updated
     * @throws NotFoundException     if job doesn't exist
     * @throws IllegalStateException if job is not in PENDING status
     */
    Mono<Void> updateJobToProcessing(UUID jobId);

    /**
     * Updates the job status to COMPLETED with file path and expiration.
     * Called when the export worker successfully completes the export.
     *
     * @param jobId     The job ID to update
     * @param filePath  Path to the exported file in storage
     * @param expiresAt Timestamp when the file will expire
     * @return Mono completing when the status is updated
     * @throws NotFoundException     if job doesn't exist
     * @throws IllegalStateException if job is not in PROCESSING status
     */
    Mono<Void> updateJobToCompleted(UUID jobId, String filePath, Instant expiresAt);

    /**
     * Updates the job status to FAILED with an error message.
     * Called when the export worker encounters an error.
     *
     * @param jobId        The job ID to update
     * @param errorMessage Description of the error that occurred
     * @return Mono completing when the status is updated
     * @throws NotFoundException if job doesn't exist
     */
    Mono<Void> updateJobToFailed(UUID jobId, String errorMessage);

    /**
     * Finds all expired export jobs across all workspaces.
     *
     * <p><strong>Security Note:</strong> This method operates across ALL workspaces and should ONLY be called
     * by system-level cleanup jobs (e.g., {@code DatasetExportCleanupJob}). The caller MUST set
     * {@link RequestContext#SYSTEM_USER} in the reactive context before calling this method.</p>
     *
     * @param now   The current timestamp to compare against expiration
     * @param limit Maximum number of expired jobs to return
     * @return Mono emitting list of expired export jobs across all workspaces
     */
    Mono<List<DatasetExportJob>> findExpiredCompletedJobs(Instant now, int limit);

    /**
     * Finds all failed export jobs that have been viewed by users across all workspaces.
     *
     * <p><strong>Security Note:</strong> This method operates across ALL workspaces and should ONLY be called
     * by system-level cleanup jobs (e.g., {@code DatasetExportCleanupJob}). The caller MUST set
     * {@link RequestContext#SYSTEM_USER} in the reactive context before calling this method.</p>
     *
     * @param limit Maximum number of viewed failed jobs to return
     * @return Mono emitting list of viewed failed export jobs across all workspaces
     */
    Mono<List<DatasetExportJob>> findViewedFailedJobs(int limit);

    /**
     * Deletes expired export jobs by their IDs across all workspaces.
     *
     * <p><strong>Security Note:</strong> This method operates across ALL workspaces and should ONLY be called
     * by system-level cleanup jobs (e.g., {@code DatasetExportCleanupJob}). The caller MUST set
     * {@link RequestContext#SYSTEM_USER} in the reactive context before calling this method.</p>
     *
     * @param jobIds Set of job IDs to delete
     * @return Mono emitting number of deleted records
     */
    Mono<Integer> deleteExpiredJobs(Set<UUID> jobIds);
}

@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class DatasetExportJobServiceImpl implements DatasetExportJobService {

    private static final Set<DatasetExportStatus> IN_PROGRESS_STATUSES = Set.of(
            DatasetExportStatus.PENDING,
            DatasetExportStatus.PROCESSING);

    public static final String EXPORT_JOB_NOT_FOUND = "Export job not found: '%s'";
    public static final String INVALID_STATE_TRANSITION = "Invalid state transition for export job: '%s'. Current status does not allow this operation.";

    private final @NonNull IdGenerator idGenerator;
    private final @NonNull TransactionTemplate template;

    @Override
    public Mono<DatasetExportJob> createJob(@NonNull UUID datasetId, @NonNull Duration ttl,
            @Nullable UUID versionId) {
        return Mono.deferContextual(ctx -> {
            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);
            String userName = ctx.get(RequestContext.USER_NAME);

            return Mono.fromCallable(() -> {
                UUID jobId = idGenerator.generateId();
                Instant now = Instant.now();
                Instant expiresAt = now.plus(ttl);

                DatasetExportJob newJob = DatasetExportJob.builder()
                        .id(jobId)
                        .datasetId(datasetId)
                        .versionId(versionId)
                        .status(DatasetExportStatus.PENDING)
                        .createdAt(now)
                        .lastUpdatedAt(now)
                        .expiresAt(expiresAt)
                        .createdBy(userName)
                        .build();

                template.inTransaction(WRITE, handle -> {
                    var dao = handle.attach(DatasetExportJobDAO.class);
                    dao.save(newJob, workspaceId);
                    return null;
                });

                log.info("Created export job: '{}' for dataset: '{}' version: '{}' in workspace: '{}'", jobId,
                        datasetId, versionId, workspaceId);

                return newJob;
            }).subscribeOn(Schedulers.boundedElastic());
        });
    }

    @Override
    public Mono<List<DatasetExportJob>> findInProgressJobs(@NonNull UUID datasetId) {
        return Mono.deferContextual(ctx -> {
            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);

            return Mono.fromCallable(() -> template.inTransaction(READ_ONLY, handle -> {
                var dao = handle.attach(DatasetExportJobDAO.class);
                List<DatasetExportJob> existingJobs = dao.findInProgressByDataset(workspaceId, datasetId,
                        IN_PROGRESS_STATUSES);

                if (!existingJobs.isEmpty()) {
                    log.info("Found '{}' existing in-progress export job(s) for dataset: '{}'", existingJobs.size(),
                            datasetId);
                }

                return existingJobs;
            })).subscribeOn(Schedulers.boundedElastic());
        });
    }

    @Override
    public Mono<List<DatasetExportJob>> findAllJobs() {
        return Mono.deferContextual(ctx -> {
            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);

            return Mono.fromCallable(() -> template.inTransaction(READ_ONLY, handle -> {
                var dao = handle.attach(DatasetExportJobDAO.class);
                List<DatasetExportJob> jobs = dao.findByWorkspace(workspaceId);

                log.debug("Found '{}' export job(s) for workspace: '{}'", jobs.size(), workspaceId);

                return jobs;
            })).subscribeOn(Schedulers.boundedElastic());
        });
    }

    @Override
    public Mono<DatasetExportJob> getJob(@NonNull UUID jobId) {
        return Mono.deferContextual(ctx -> {
            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);

            return Mono.fromCallable(() -> template.inTransaction(READ_ONLY, handle -> {
                var dao = handle.attach(DatasetExportJobDAO.class);

                return dao.findById(workspaceId, jobId)
                        .orElseThrow(() -> new NotFoundException(EXPORT_JOB_NOT_FOUND.formatted(jobId)));
            })).subscribeOn(Schedulers.boundedElastic());
        });
    }

    @Override
    public Mono<Void> markJobAsViewed(@NonNull UUID jobId) {
        return Mono.deferContextual(ctx -> {
            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);
            String userName = ctx.get(RequestContext.USER_NAME);

            return Mono.fromRunnable(() -> template.inTransaction(WRITE, handle -> {
                var dao = handle.attach(DatasetExportJobDAO.class);

                // Verify job exists
                var job = dao.findById(workspaceId, jobId)
                        .orElseThrow(() -> new NotFoundException(EXPORT_JOB_NOT_FOUND.formatted(jobId)));

                // Idempotent: if already viewed, do nothing
                if (job.viewedAt() != null) {
                    log.debug("Export job '{}' already marked as viewed, skipping", jobId);
                    return null;
                }

                // Update viewed_at and last_updated_by
                dao.updateViewedAt(workspaceId, jobId, Instant.now(), userName);
                log.debug("Marked export job '{}' as viewed by '{}'", jobId, userName);

                return null;
            })).subscribeOn(Schedulers.boundedElastic()).then();
        });
    }

    @Override
    public Mono<Void> updateJobToProcessing(@NonNull UUID jobId) {
        return Mono.deferContextual(ctx -> {
            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);
            String userName = ctx.get(RequestContext.USER_NAME);

            return Mono.fromCallable(() -> {
                template.inTransaction(WRITE, handle -> {
                    var dao = handle.attach(DatasetExportJobDAO.class);
                    int updated = dao.markPendingJobAsProcessing(workspaceId, jobId, userName);
                    verifyJobUpdatedToStatus(updated, jobId, DatasetExportStatus.PROCESSING, workspaceId, dao);
                    return null;
                });
                return null;
            }).subscribeOn(Schedulers.boundedElastic()).then();
        });
    }

    @Override
    public Mono<Void> updateJobToCompleted(@NonNull UUID jobId, @NonNull String filePath,
            @NonNull Instant expiresAt) {
        return Mono.deferContextual(ctx -> {
            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);
            String userName = ctx.get(RequestContext.USER_NAME);

            return Mono.fromCallable(() -> {
                template.inTransaction(WRITE, handle -> {
                    var dao = handle.attach(DatasetExportJobDAO.class);
                    int updated = dao.updateToCompleted(workspaceId, jobId, DatasetExportStatus.COMPLETED, filePath,
                            expiresAt, userName);
                    verifyJobUpdatedToStatus(updated, jobId, DatasetExportStatus.COMPLETED, workspaceId, dao);
                    return null;
                });
                return null;
            }).subscribeOn(Schedulers.boundedElastic()).then();
        });
    }

    @Override
    public Mono<Void> updateJobToFailed(@NonNull UUID jobId, @NonNull String errorMessage) {
        return Mono.deferContextual(ctx -> {
            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);
            String userName = ctx.get(RequestContext.USER_NAME);

            return Mono.fromCallable(() -> {
                template.inTransaction(WRITE, handle -> {
                    var dao = handle.attach(DatasetExportJobDAO.class);
                    int updated = dao.updateToFailed(workspaceId, jobId, errorMessage, userName);
                    verifyJobUpdatedToStatus(updated, jobId, DatasetExportStatus.FAILED, workspaceId, dao);
                    return null;
                });
                return null;
            }).subscribeOn(Schedulers.boundedElastic()).then();
        });
    }

    /**
     * Verifies that a job was successfully updated to the expected status and logs the transition.
     * <p>
     * If the update affected 0 rows, checks if the job is already in the expected state
     * (idempotent). If not, throws an appropriate exception.
     *
     * @param updatedRows    The number of rows affected by the update operation
     * @param jobId          The ID of the job being updated
     * @param expectedStatus The status the job should now be in
     * @param workspaceId    The workspace ID for security
     * @param dao            The DAO to query the current job state
     * @throws NotFoundException     if the job doesn't exist or doesn't belong to workspace
     * @throws IllegalStateException if the job exists but is in an unexpected state
     */
    private void verifyJobUpdatedToStatus(int updatedRows, UUID jobId, DatasetExportStatus expectedStatus,
            String workspaceId, DatasetExportJobDAO dao) {
        if (updatedRows > 0) {
            log.info("Export job '{}' transitioned to status '{}'", jobId, expectedStatus);
            return;
        }

        var job = dao.findById(workspaceId, jobId)
                .orElseThrow(() -> new NotFoundException(EXPORT_JOB_NOT_FOUND.formatted(jobId)));

        // Job already in expected state - idempotent success
        if (job.status() == expectedStatus) {
            log.debug("Export job '{}' already in '{}' state", jobId, expectedStatus);
            return;
        }

        // Job exists but in wrong state - state machine violation
        log.warn("Export job '{}' state transition failed: expected '{}' but found '{}'",
                jobId, expectedStatus, job.status());
        throw new IllegalStateException(INVALID_STATE_TRANSITION.formatted(jobId));
    }

    @Override
    public Mono<List<DatasetExportJob>> findExpiredCompletedJobs(@NonNull Instant now, int limit) {
        return Mono.deferContextual(ctx -> {
            String userName = ctx.get(RequestContext.USER_NAME);
            return Mono.fromCallable(() -> template.inTransaction(READ_ONLY, handle -> {
                var dao = handle.attach(DatasetExportJobDAO.class);
                return dao.findExpiredCompletedJobs(userName, now, limit);
            })).subscribeOn(Schedulers.boundedElastic());
        });
    }

    @Override
    public Mono<List<DatasetExportJob>> findViewedFailedJobs(int limit) {
        return Mono.deferContextual(ctx -> {
            String userName = ctx.get(RequestContext.USER_NAME);
            return Mono.fromCallable(() -> template.inTransaction(READ_ONLY, handle -> {
                var dao = handle.attach(DatasetExportJobDAO.class);
                return dao.findViewedFailedJobs(userName, limit);
            })).subscribeOn(Schedulers.boundedElastic());
        });
    }

    @Override
    public Mono<Integer> deleteExpiredJobs(@NonNull Set<UUID> jobIds) {
        if (jobIds.isEmpty()) {
            return Mono.just(0);
        }

        return Mono.deferContextual(ctx -> {
            String userName = ctx.get(RequestContext.USER_NAME);
            return Mono.fromCallable(() -> template.inTransaction(WRITE, handle -> {
                var dao = handle.attach(DatasetExportJobDAO.class);
                return dao.deleteJobsByIds(userName, jobIds);
            })).subscribeOn(Schedulers.boundedElastic());
        });
    }
}

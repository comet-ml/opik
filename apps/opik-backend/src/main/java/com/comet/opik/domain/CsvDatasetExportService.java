package com.comet.opik.domain;

import com.comet.opik.api.DatasetExportJob;
import com.comet.opik.api.DatasetExportStatus;
import com.comet.opik.domain.attachment.FileService;
import com.comet.opik.infrastructure.DatasetExportConfig;
import com.comet.opik.infrastructure.FeatureFlags;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.lock.LockService;
import com.google.inject.ImplementedBy;
import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RStreamReactive;
import org.redisson.api.RedissonReactiveClient;
import org.redisson.api.stream.StreamAddArgs;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.io.InputStream;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@ImplementedBy(CsvDatasetExportServiceImpl.class)
public interface CsvDatasetExportService {

    /**
     * Starts a new CSV export job for the specified dataset.
     * If an export job is already in progress for this dataset, returns the existing job.
     * The export is processed asynchronously via a Redis stream.
     *
     * When dataset versioning is enabled:
     * - If versionId is provided, exports that specific version
     * - If versionId is null, exports the latest version
     *
     * When dataset versioning is disabled:
     * - Always uses the legacy dataset_items table (versionId is ignored)
     *
     * @param datasetId The dataset ID to export
     * @param versionId Optional version ID. If null and versioning is enabled, uses latest version.
     *                  Ignored when versioning is disabled.
     * @return Mono emitting the created or existing export job
     * @throws IllegalStateException if dataset export feature is disabled
     */
    Mono<DatasetExportJob> startExport(UUID datasetId, @Nullable UUID versionId);

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
     * Finds all export jobs for the current workspace.
     * Returns all jobs regardless of status - the cleanup job handles removing old jobs.
     * This is used to restore the export panel state after page refresh.
     *
     * @return Mono emitting list of all export jobs for the workspace
     */
    Mono<List<DatasetExportJob>> findAllJobs();

    /**
     * Downloads the exported CSV file for a completed job.
     * This proxies access to the file storage (MinIO/S3) to avoid exposing internal URLs.
     *
     * @param jobId The job ID to download
     * @return Mono emitting InputStream of the CSV file content
     * @throws NotFoundException if job doesn't exist or file is not available
     * @throws IllegalStateException if job is not in COMPLETED status
     */
    Mono<InputStream> downloadExport(UUID jobId);

    /**
     * Deletes a completed or failed export job and its associated file from storage.
     * Only COMPLETED and FAILED jobs can be deleted by users.
     * This operation is idempotent - if the job doesn't exist, it's considered already deleted.
     *
     * @param jobId The job ID to delete
     * @return Mono completing when the job and file are deleted
     * @throws IllegalStateException if job is not in COMPLETED or FAILED status, or dataset export feature is disabled
     */
    Mono<Void> deleteExport(UUID jobId);
}

@Slf4j
@Singleton
class CsvDatasetExportServiceImpl implements CsvDatasetExportService {

    public static final String LOCK_KEY_PATTERN = "dataset-export:lock:%s:%s";

    private final DatasetExportJobService jobService;
    private final RedissonReactiveClient redisClient;
    private final DatasetExportConfig exportConfig;
    private final LockService lockService;
    private final FileService fileService;
    private final DatasetVersionService versionService;
    private final FeatureFlags featureFlags;

    @Inject
    public CsvDatasetExportServiceImpl(
            @NonNull DatasetExportJobService jobService,
            @NonNull RedissonReactiveClient redisClient,
            @NonNull @Config("datasetExport") DatasetExportConfig exportConfig,
            @NonNull LockService lockService,
            @NonNull FileService fileService,
            @NonNull DatasetVersionService versionService,
            @NonNull FeatureFlags featureFlags) {
        this.jobService = jobService;
        this.redisClient = redisClient;
        this.exportConfig = exportConfig;
        this.lockService = lockService;
        this.fileService = fileService;
        this.versionService = versionService;
        this.featureFlags = featureFlags;
    }

    @Override
    public Mono<DatasetExportJob> startExport(@NonNull UUID datasetId, @Nullable UUID versionId) {
        if (!exportConfig.isEnabled()) {
            log.warn("CSV dataset export is disabled; skipping export for dataset: '{}'", datasetId);
            return Mono.error(new IllegalStateException("Dataset export is disabled"));
        }

        log.info("Starting CSV export for dataset: '{}', versionId: '{}'", datasetId, versionId);

        return Mono.deferContextual(ctx -> {
            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);

            // Resolve versionId based on feature flag and provided value
            UUID resolvedVersionId = resolveVersionId(datasetId, versionId, workspaceId);

            // Check for existing in-progress jobs first (without lock)
            return jobService.findInProgressJobs(datasetId)
                    .flatMap(existingJobs -> {
                        if (!existingJobs.isEmpty()) {
                            DatasetExportJob existingJob = existingJobs.getFirst();
                            log.info("Found existing in-progress export job: '{}'", existingJob.id());
                            return Mono.just(existingJob);
                        }

                        // No existing job, acquire lock and create new one
                        String lockKey = formatLockKey(workspaceId, datasetId);
                        return executeWithLock(lockKey, workspaceId, datasetId, resolvedVersionId);
                    });
        });
    }

    /**
     * Resolves the versionId based on feature flag and provided value.
     *
     * @param datasetId the dataset ID
     * @param versionId the provided versionId (may be null)
     * @param workspaceId the workspace ID
     * @return the resolved versionId, or null if using legacy table
     */
    private UUID resolveVersionId(UUID datasetId, @Nullable UUID versionId, String workspaceId) {
        if (!featureFlags.isDatasetVersioningEnabled()) {
            // When versioning is disabled, always use null (legacy table)
            log.info("Dataset versioning is disabled, using legacy table for export");
            return null;
        }

        if (versionId != null) {
            // Versioning enabled and versionId provided - use it
            return versionId;
        }

        // Versioning enabled but no versionId provided - get latest version
        return versionService.getLatestVersion(datasetId, workspaceId)
                .map(v -> v.id())
                .orElse(null);
    }

    private Mono<DatasetExportJob> executeWithLock(String lockKey, String workspaceId, UUID datasetId,
            UUID versionId) {
        Mono<DatasetExportJob> action = Mono.defer(() -> jobService.findInProgressJobs(datasetId)
                .flatMap(existingJobs -> {
                    // Double-check after acquiring lock
                    if (!existingJobs.isEmpty()) {
                        DatasetExportJob existingJob = existingJobs.getFirst();
                        log.info("Found existing in-progress export job after lock: '{}'", existingJob.id());
                        return Mono.just(existingJob);
                    }

                    // Create new export job and publish to Redis stream
                    // TTL is taken from config (defaultTtl)
                    return jobService.createJob(datasetId, exportConfig.getDefaultTtl().toJavaDuration(), versionId)
                            .flatMap(job -> publishToRedisStream(job, workspaceId)
                                    .thenReturn(job));
                }));

        return lockService.executeWithLock(new LockService.Lock(lockKey), action);
    }

    private Mono<Void> publishToRedisStream(DatasetExportJob job, String workspaceId) {
        log.info("Publishing export job to Redis stream: '{}'", job.id());

        DatasetExportMessage message = DatasetExportMessage.builder()
                .jobId(job.id())
                .datasetId(job.datasetId())
                .workspaceId(workspaceId)
                .versionId(job.versionId())
                .build();

        RStreamReactive<String, DatasetExportMessage> stream = redisClient.getStream(
                exportConfig.getStreamName(),
                exportConfig.getCodec());

        return stream.add(StreamAddArgs.entry(DatasetExportConfig.PAYLOAD_FIELD, message))
                .doOnNext(messageId -> log.info(
                        "Export job published to Redis stream: jobId='{}', messageId='{}'",
                        job.id(), messageId))
                .doOnError(throwable -> log.error(
                        "Failed to publish export job to Redis stream: jobId='{}'",
                        job.id(), throwable))
                .then();
    }

    private static String formatLockKey(String workspaceId, UUID datasetId) {
        return LOCK_KEY_PATTERN.formatted(workspaceId, datasetId);
    }

    @Override
    public Mono<DatasetExportJob> getJob(@NonNull UUID jobId) {
        return jobService.getJob(jobId);
    }

    @Override
    public Mono<Void> markJobAsViewed(@NonNull UUID jobId) {
        return jobService.markJobAsViewed(jobId);
    }

    @Override
    public Mono<List<DatasetExportJob>> findAllJobs() {
        return jobService.findAllJobs();
    }

    @Override
    public Mono<InputStream> downloadExport(@NonNull UUID jobId) {
        return jobService.getJob(jobId)
                .flatMap(job -> {

                    if (job.status() == DatasetExportStatus.FAILED) {
                        return Mono
                                .error(new BadRequestException(
                                        "Export job '%s' failed: %s"
                                                .formatted(jobId, job.errorMessage() != null
                                                        ? job.errorMessage()
                                                        : "Unknown error")));
                    }

                    if (job.status() != DatasetExportStatus.COMPLETED) {
                        return Mono
                                .error(new BadRequestException(
                                        "Export job '%s' is not ready for download (status: %s)"
                                                .formatted(jobId, job.status())));
                    }

                    log.info("Downloading export file for job: '{}', filePath: '{}'", jobId, job.filePath());

                    return Mono.fromCallable(() -> fileService.download(job.filePath()))
                            .subscribeOn(Schedulers.boundedElastic());
                });
    }

    @Override
    public Mono<Void> deleteExport(@NonNull UUID jobId) {
        if (!exportConfig.isEnabled()) {
            log.warn("CSV dataset export is disabled; cannot delete export job: '{}'", jobId);
            return Mono.error(new IllegalStateException("Dataset export is disabled"));
        }

        log.info("Deleting export job: '{}'", jobId);

        return jobService.getJob(jobId)
                .flatMap(job -> {
                    // Only allow deletion of completed or failed jobs
                    if (job.status() != DatasetExportStatus.COMPLETED
                            && job.status() != DatasetExportStatus.FAILED) {
                        log.warn("Cannot delete export job '{}' with status '{}'", jobId, job.status());
                        return Mono.error(new BadRequestException(
                                "Cannot delete export job '%s'. Only completed or failed jobs can be deleted."
                                        .formatted(jobId)));
                    }

                    // Delete file from storage first (if file path exists)
                    return Mono.fromRunnable(() -> deleteFileIfExists(job.filePath()))
                            .subscribeOn(Schedulers.boundedElastic())
                            // Then delete job from database
                            .then(jobService.deleteJob(jobId));
                })
                // Idempotent: if job not found, consider it already deleted
                .onErrorResume(NotFoundException.class, e -> {
                    log.info("Export job '{}' not found, already deleted (idempotent)", jobId);
                    return Mono.empty();
                });
    }

    /**
     * Safely deletes a file from storage if the file path is not null.
     * Handles errors gracefully if the file doesn't exist.
     *
     * @param filePath The file path to delete (may be null)
     */
    private void deleteFileIfExists(@Nullable String filePath) {
        if (filePath == null) {
            log.debug("No file path to delete");
            return;
        }

        try {
            fileService.deleteObjects(Set.of(filePath));
            log.info("Deleted export file: '{}'", filePath);
        } catch (Exception e) {
            // Log warning but don't fail - file might already be deleted or never existed
            log.warn("Failed to delete export file '{}': {}", filePath, e.getMessage());
        }
    }
}

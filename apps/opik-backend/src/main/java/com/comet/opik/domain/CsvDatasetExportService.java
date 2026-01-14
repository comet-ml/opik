package com.comet.opik.domain;

import com.comet.opik.api.DatasetExportJob;
import com.comet.opik.api.DatasetExportStatus;
import com.comet.opik.domain.attachment.FileService;
import com.comet.opik.infrastructure.DatasetExportConfig;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.lock.LockService;
import com.google.inject.ImplementedBy;
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
import java.util.UUID;

@ImplementedBy(CsvDatasetExportServiceImpl.class)
public interface CsvDatasetExportService {

    Mono<DatasetExportJob> startExport(UUID datasetId);

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

    @Inject
    public CsvDatasetExportServiceImpl(
            @NonNull DatasetExportJobService jobService,
            @NonNull RedissonReactiveClient redisClient,
            @NonNull @Config("datasetExport") DatasetExportConfig exportConfig,
            @NonNull LockService lockService,
            @NonNull FileService fileService) {
        this.jobService = jobService;
        this.redisClient = redisClient;
        this.exportConfig = exportConfig;
        this.lockService = lockService;
        this.fileService = fileService;
    }

    @Override
    public Mono<DatasetExportJob> startExport(@NonNull UUID datasetId) {
        if (!exportConfig.isEnabled()) {
            log.warn("CSV dataset export is disabled; skipping export for dataset: '{}'", datasetId);
            return Mono.error(new IllegalStateException("Dataset export is disabled"));
        }

        log.info("Starting CSV export for dataset: '{}'", datasetId);

        return Mono.deferContextual(ctx -> {
            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);

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
                        return executeWithLock(lockKey, workspaceId, datasetId);
                    });
        });
    }

    private Mono<DatasetExportJob> executeWithLock(String lockKey, String workspaceId, UUID datasetId) {
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
                    return jobService.createJob(datasetId, exportConfig.getDefaultTtl().toJavaDuration())
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
}

package com.comet.opik.domain;

import com.comet.opik.api.ExportJob;
import com.comet.opik.api.ExportParams;
import com.comet.opik.api.ExportStatus;
import com.comet.opik.domain.attachment.FileService;
import com.comet.opik.infrastructure.ExportConfig;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.lock.LockService;
import com.comet.opik.infrastructure.redis.RedisStreamUtils;
import com.google.inject.ImplementedBy;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.ServerErrorException;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RStreamReactive;
import org.redisson.api.RedissonReactiveClient;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.io.InputStream;
import java.util.List;
import java.util.UUID;

@ImplementedBy(CsvExportServiceImpl.class)
public interface CsvExportService {

    /**
     * Starts a new CSV export job for the specified dataset.
     * If an export job is already in progress for this dataset, returns the existing job.
     * The export is processed asynchronously via a Redis stream.
     *
     * @param datasetId The dataset ID to export
     * @return Mono emitting the created or existing export job
     * @throws IllegalStateException if dataset export feature is disabled
     */
    Mono<ExportJob> startExport(ExportParams params, String resourceName);

    /**
     * Retrieves an export job by its ID.
     *
     * @param jobId The job ID to retrieve
     * @return Mono emitting the export job
     * @throws NotFoundException if job doesn't exist or doesn't belong to the current workspace
     */
    Mono<ExportJob> getJob(UUID jobId);

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
    Mono<List<ExportJob>> findAllJobs();

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
class CsvExportServiceImpl implements CsvExportService {

    public static final String LOCK_KEY_PATTERN = "export:lock:%s:%s:%s:%s";

    private final ExportJobService jobService;
    private final RedissonReactiveClient redisClient;
    private final ExportConfig exportConfig;
    private final LockService lockService;
    private final FileService fileService;

    @Inject
    public CsvExportServiceImpl(
            @NonNull ExportJobService jobService,
            @NonNull RedissonReactiveClient redisClient,
            @NonNull @Config("exportJobs") ExportConfig exportConfig,
            @NonNull LockService lockService,
            @NonNull FileService fileService) {
        this.jobService = jobService;
        this.redisClient = redisClient;
        this.exportConfig = exportConfig;
        this.lockService = lockService;
        this.fileService = fileService;
    }

    @Override
    public Mono<ExportJob> startExport(@NonNull ExportParams params, String resourceName) {
        if (!exportConfig.isEnabledFor(params.exportType())) {
            log.warn("CSV export is disabled for type '{}'; skipping", params.exportType());
            // A disabled surface is a deployment choice, not a server fault: report it as such rather than a 500.
            return Mono.error(new ServerErrorException(
                    "Export is not enabled for type '%s' on this installation".formatted(params.exportType()),
                    Response.Status.NOT_IMPLEMENTED));
        }

        log.info("Starting CSV '{}' export", params.exportType());

        return Mono.deferContextual(ctx -> {
            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);
            String userName = ctx.get(RequestContext.USER_NAME);

            // Check for existing in-progress jobs first (without lock)
            return findMatchingInProgressJob(params)
                    .flatMap(existingJob -> {
                        log.info("Found existing in-progress export job: '{}'", existingJob.id());
                        return Mono.just(existingJob);
                    })
                    .switchIfEmpty(Mono.defer(() -> {
                        // No existing job, acquire lock and create new one
                        String lockKey = formatLockKey(workspaceId, userName, params);
                        return executeWithLock(lockKey, workspaceId, params, resourceName);
                    }));
        });
    }

    /**
     * An in-progress job is reusable only when it covers exactly the same rows, which the params hash already
     * encodes: two comparisons of different experiments on one dataset hash differently and so never share a file.
     */
    private Mono<ExportJob> findMatchingInProgressJob(ExportParams params) {
        return jobService.findInProgressJobs(params)
                .flatMap(existingJobs -> existingJobs.stream()
                        .findFirst()
                        .map(Mono::just)
                        .orElseGet(Mono::empty));
    }

    private Mono<ExportJob> executeWithLock(String lockKey, String workspaceId, ExportParams params,
            String resourceName) {
        Mono<ExportJob> action = Mono
                .defer(() -> findMatchingInProgressJob(params)
                        .flatMap(existingJob -> {
                            // Double-check after acquiring lock
                            log.info("Found existing in-progress export job after lock: '{}'", existingJob.id());
                            return Mono.just(existingJob);
                        })
                        .switchIfEmpty(Mono.defer(() ->
                        // Create new export job and publish to Redis stream
                        // TTL is taken from config (defaultTtl)
                        jobService.createJob(params, resourceName, exportConfig.getDefaultTtl().toJavaDuration())
                                .flatMap(job -> publishToRedisStream(job, workspaceId)
                                        .thenReturn(job)))));

        return lockService.executeWithLock(new LockService.Lock(lockKey), action);
    }

    private Mono<Void> publishToRedisStream(ExportJob job, String workspaceId) {
        return Mono.deferContextual(ctx -> {
            log.info("Publishing export job to Redis stream: '{}'", job.id());

            ExportMessage message = ExportMessage.builder()
                    .jobId(job.id())
                    .workspaceId(workspaceId)
                    .workspaceName(ctx.getOrDefault(RequestContext.WORKSPACE_NAME, null))
                    .build();

            RStreamReactive<String, ExportMessage> stream = redisClient.getStream(
                    exportConfig.getStreamName(),
                    exportConfig.getCodec());

            return stream.add(RedisStreamUtils.buildAddArgs(
                    ExportConfig.PAYLOAD_FIELD, message, exportConfig))
                    .doOnNext(messageId -> log.info(
                            "Export job published to Redis stream: jobId='{}', messageId='{}'",
                            job.id(), messageId))
                    .doOnError(throwable -> log.error(
                            "Failed to publish export job to Redis stream: jobId='{}'",
                            job.id(), throwable))
                    .then();
        });
    }

    /**
     * Keyed by caller as well as params: jobs are owned by whoever started them, so two users asking for the same
     * rows each get their own job and must not serialise behind one another's lock.
     */
    private static String formatLockKey(String workspaceId, String userName, ExportParams params) {
        return LOCK_KEY_PATTERN.formatted(workspaceId, userName, params.exportType(), params.canonicalHash());
    }

    @Override
    public Mono<ExportJob> getJob(@NonNull UUID jobId) {
        return jobService.getJob(jobId);
    }

    @Override
    public Mono<Void> markJobAsViewed(@NonNull UUID jobId) {
        return jobService.markJobAsViewed(jobId);
    }

    @Override
    public Mono<List<ExportJob>> findAllJobs() {
        return jobService.findAllJobs();
    }

    @Override
    public Mono<InputStream> downloadExport(@NonNull UUID jobId) {
        return jobService.getJob(jobId)
                .flatMap(job -> {

                    if (job.status() == ExportStatus.FAILED) {
                        return Mono
                                .error(new BadRequestException(
                                        "Export job '%s' failed: %s"
                                                .formatted(jobId, job.errorMessage() != null
                                                        ? job.errorMessage()
                                                        : "Unknown error")));
                    }

                    if (job.status() != ExportStatus.COMPLETED) {
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

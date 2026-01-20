package com.comet.opik.domain;

import com.comet.opik.api.DatasetExportJob;
import com.comet.opik.domain.attachment.FileService;
import com.comet.opik.infrastructure.DatasetExportConfig;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.lock.LockService;
import io.dropwizard.jobs.Job;
import io.dropwizard.jobs.annotations.On;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.InterruptableJob;
import org.quartz.JobExecutionContext;
import reactor.core.publisher.Mono;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.time.Instant;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * Job responsible for cleaning up expired dataset export jobs.
 * This includes:
 * 1. Deleting expired files from S3/MinIO
 * 2. Removing expired records from the database
 *
 * Runs every hour at the top of the hour.
 */
@Singleton
@Slf4j
@DisallowConcurrentExecution
@On(value = "0 0 * * * ?", timeZone = "UTC") // every hour at the top of the hour
public class DatasetExportCleanupJob extends Job implements InterruptableJob {

    private final DatasetExportJobService exportJobService;
    private final FileService fileService;
    private final LockService lockService;
    private final DatasetExportConfig exportConfig;

    private final AtomicBoolean interrupted = new AtomicBoolean(false);

    @Inject
    public DatasetExportCleanupJob(
            @NonNull DatasetExportJobService exportJobService,
            @NonNull FileService fileService,
            @NonNull LockService lockService,
            @NonNull @Config("datasetExport") DatasetExportConfig exportConfig) {
        this.exportJobService = exportJobService;
        this.fileService = fileService;
        this.lockService = lockService;
        this.exportConfig = exportConfig;
    }

    @Override
    public void doJob(JobExecutionContext context) {
        log.info("Starting dataset export cleanup");

        // Check for interruption before starting
        if (interrupted.get()) {
            log.info("Job interrupted before execution, skipping cleanup");
            return;
        }

        var lock = new LockService.Lock("dataset_export_cleanup");

        try {
            lockService.bestEffortLock(
                    lock,
                    Mono.defer(this::cleanupExpiredJobsReactive)
                            .contextWrite(ctx -> ctx.put(RequestContext.USER_NAME, RequestContext.SYSTEM_USER)),
                    Mono.defer(() -> {
                        log.info("Could not acquire lock for dataset export cleanup, another instance is running");
                        return Mono.empty();
                    }),
                    exportConfig.getCleanupTimeout().toJavaDuration(),
                    exportConfig.getCleanupLockWaitTime().toJavaDuration())
                    .block();

            log.info("Dataset export cleanup completed successfully");

        } catch (IllegalStateException exception) {
            // Lock acquisition/state errors - expected failure mode
            log.warn("Dataset export cleanup failed due to lock/state issue", exception);
        } catch (RuntimeException exception) {
            // Check if the cause is an InterruptedException and restore interrupt status
            Throwable cause = exception.getCause();
            while (cause != null) {
                if (cause instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                    log.warn("Dataset export cleanup was interrupted", exception);
                    return; // Exit early on interrupt
                }
                cause = cause.getCause();
            }
            // Log unexpected runtime errors
            log.error("Failed to cleanup expired dataset export jobs", exception);
        }
    }

    /**
     * Reactive wrapper for cleanup operation.
     */
    private Mono<Void> cleanupExpiredJobsReactive() {
        Instant now = Instant.now();

        if (interrupted.get()) {
            log.info("Job interrupted during execution");
            return Mono.empty();
        }

        // Execute both cleanup operations concurrently
        Mono<Integer> completedCleanup = cleanupExpiredCompletedJobsBatch(now, 0).then(Mono.just(0));
        Mono<Integer> failedCleanup = cleanupViewedFailedJobsBatch(0).then(Mono.just(0));

        return Mono.zip(completedCleanup, failedCleanup).then();
    }

    /**
     * Cleans up expired completed export jobs reactively.
     * Processes in batches until all expired jobs are cleaned or interrupted.
     */
    private Mono<Void> cleanupExpiredCompletedJobsBatch(Instant now, int totalCleaned) {
        if (interrupted.get()) {
            log.info("Cleanup interrupted after processing '{}' expired completed jobs", totalCleaned);
            return Mono.empty();
        }

        return exportJobService.findExpiredCompletedJobs(now, exportConfig.getCleanupBatchSize())
                .flatMap(expiredJobs -> {
                    if (expiredJobs.isEmpty()) {
                        if (totalCleaned > 0) {
                            log.info("Finished cleaning up '{}' expired completed dataset export jobs", totalCleaned);
                        } else {
                            log.debug("No expired completed dataset export jobs found");
                        }
                        return Mono.empty();
                    }

                    log.info("Processing batch of '{}' expired completed dataset export jobs", expiredJobs.size());

                    // Delete files from S3/MinIO
                    Set<String> filePaths = expiredJobs.stream()
                            .map(DatasetExportJob::filePath)
                            .filter(StringUtils::isNotBlank)
                            .collect(Collectors.toSet());

                    if (!filePaths.isEmpty()) {
                        log.debug("Deleting '{}' files from S3/MinIO", filePaths.size());
                        fileService.deleteObjects(filePaths);
                    }

                    // Delete records from database
                    Set<java.util.UUID> jobIds = expiredJobs.stream()
                            .map(DatasetExportJob::id)
                            .collect(Collectors.toSet());

                    return exportJobService.deleteExpiredJobs(jobIds)
                            .flatMap(deletedCount -> {
                                int newTotal = totalCleaned + deletedCount;
                                log.debug("Deleted '{}' expired completed jobs in this batch (total: '{}')",
                                        deletedCount, newTotal);
                                // Recursively process next batch
                                return cleanupExpiredCompletedJobsBatch(now, newTotal);
                            });
                });
    }

    /**
     * Cleans up viewed failed export jobs reactively.
     * Also deletes any files that may exist (in case failure happened after partial upload).
     * Processes in batches until all viewed failed jobs are cleaned or interrupted.
     */
    private Mono<Void> cleanupViewedFailedJobsBatch(int totalCleaned) {
        if (interrupted.get()) {
            log.info("Cleanup interrupted after processing '{}' viewed failed jobs", totalCleaned);
            return Mono.empty();
        }

        return exportJobService.findViewedFailedJobs(exportConfig.getCleanupBatchSize())
                .flatMap(viewedFailedJobs -> {
                    if (viewedFailedJobs.isEmpty()) {
                        if (totalCleaned > 0) {
                            log.info("Finished cleaning up '{}' viewed failed dataset export jobs", totalCleaned);
                        } else {
                            log.debug("No viewed failed dataset export jobs found");
                        }
                        return Mono.empty();
                    }

                    log.info("Processing batch of '{}' viewed failed dataset export jobs", viewedFailedJobs.size());

                    // Delete any files that may exist (in case failure happened after partial upload)
                    Set<String> filePaths = viewedFailedJobs.stream()
                            .map(DatasetExportJob::filePath)
                            .filter(StringUtils::isNotBlank)
                            .collect(Collectors.toSet());

                    if (!filePaths.isEmpty()) {
                        log.debug("Deleting '{}' files from S3/MinIO for failed jobs", filePaths.size());
                        fileService.deleteObjects(filePaths);
                        log.info("Deleted '{}' files from S3/MinIO for failed jobs", filePaths.size());
                    }

                    // Delete records from database
                    Set<java.util.UUID> jobIds = viewedFailedJobs.stream()
                            .map(DatasetExportJob::id)
                            .collect(Collectors.toSet());

                    return exportJobService.deleteExpiredJobs(jobIds)
                            .flatMap(deletedCount -> {
                                int newTotal = totalCleaned + deletedCount;
                                log.debug("Deleted '{}' viewed failed jobs in this batch (total: '{}')", deletedCount,
                                        newTotal);
                                // Recursively process next batch
                                return cleanupViewedFailedJobsBatch(newTotal);
                            });
                });
    }

    @Override
    public void interrupt() {
        interrupted.set(true);
        log.info("Dataset export cleanup successfully called interruption");
    }
}

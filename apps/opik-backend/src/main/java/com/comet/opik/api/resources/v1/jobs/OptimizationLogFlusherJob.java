package com.comet.opik.api.resources.v1.jobs;

import com.comet.opik.domain.optimization.OptimizationLogSyncService;
import com.comet.opik.infrastructure.OptimizationLogsConfig;
import com.comet.opik.infrastructure.lock.LockService;
import io.dropwizard.jobs.Job;
import io.dropwizard.jobs.annotations.Every;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.JobExecutionContext;
import org.redisson.api.RedissonReactiveClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.time.Duration;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.comet.opik.infrastructure.lock.LockService.Lock;

/**
 * Scheduled job that periodically syncs optimization logs from Redis to S3.
 * <p>
 * This job scans for active optimization log keys in Redis and triggers
 * sync for those with new logs. Uses distributed locking to prevent
 * duplicate work across multiple backend instances.
 * <p>
 * Redis key pattern scanned: opik:logs:*:meta
 * <p>
 * The job runs every 5 minutes by default (configurable via optimizationLogs.syncIntervalSeconds).
 */
@Slf4j
@Singleton
@DisallowConcurrentExecution
@Every("5min")
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class OptimizationLogFlusherJob extends Job {

    private static final Lock SCAN_LOCK_KEY = new Lock("optimization_log_flusher:scan_lock");
    private static final String META_KEY_PATTERN = "opik:logs:*:meta";

    // Pattern to extract workspace_id and optimization_id from meta key
    // opik:logs:{workspace_id}:{optimization_id}:meta
    private static final Pattern META_KEY_REGEX = Pattern.compile("opik:logs:([^:]+):([^:]+):meta");

    private final @NonNull RedissonReactiveClient redisClient;
    private final @NonNull OptimizationLogSyncService logSyncService;
    private final @NonNull LockService lockService;
    private final @NonNull @Config("optimizationLogs") OptimizationLogsConfig config;

    @Override
    public void doJob(JobExecutionContext context) {
        if (!config.isEnabled()) {
            log.debug("Optimization log flusher is disabled");
            return;
        }

        log.debug("Starting optimization log flusher job");

        // Use distributed lock to prevent overlapping scans across instances
        lockService.bestEffortLock(
                SCAN_LOCK_KEY,
                Mono.defer(this::scanAndSyncLogs),
                Mono.defer(() -> {
                    log.debug("Could not acquire scan lock, another instance is running");
                    return Mono.empty();
                }),
                Duration.ofSeconds(config.getSyncIntervalSeconds()),
                Duration.ofSeconds(10)).subscribe(
                        __ -> log.debug("Optimization log flusher job completed"),
                        error -> log.error("Optimization log flusher job failed", error));
    }

    /**
     * Scan Redis for optimization log meta keys and sync each one.
     */
    private Mono<Void> scanAndSyncLogs() {
        return redisClient.getKeys().getKeysByPattern(META_KEY_PATTERN)
                .collectList()
                .flatMap(keys -> {
                    if (keys.isEmpty()) {
                        log.debug("No optimization log keys found");
                        return Mono.empty();
                    }

                    log.info("Found '{}' optimization log keys to check", keys.size());

                    return Flux.fromIterable(keys)
                            .flatMap(this::syncLogForMetaKey)
                            .onErrorContinue((error, key) -> log.warn("Failed to sync logs for key '{}': {}",
                                    key, error.getMessage()))
                            .then();
                });
    }

    /**
     * Parse meta key and trigger sync for the optimization.
     */
    private Mono<Void> syncLogForMetaKey(String metaKey) {
        Matcher matcher = META_KEY_REGEX.matcher(metaKey);
        if (!matcher.matches()) {
            log.warn("Invalid meta key format: '{}'", metaKey);
            return Mono.empty();
        }

        String workspaceId = matcher.group(1);
        String optimizationIdStr = matcher.group(2);

        try {
            UUID optimizationId = UUID.fromString(optimizationIdStr);
            return logSyncService.syncLogsToS3(workspaceId, optimizationId);
        } catch (IllegalArgumentException e) {
            log.warn("Invalid optimization ID in meta key '{}': {}", metaKey, optimizationIdStr);
            return Mono.empty();
        }
    }
}

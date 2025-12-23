package com.comet.opik.api.resources.v1.jobs;

import com.comet.opik.domain.optimization.OptimizationLogSyncService;
import com.comet.opik.infrastructure.OptimizationLogsConfig;
import io.dropwizard.lifecycle.Managed;
import jakarta.inject.Inject;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonReactiveClient;
import org.redisson.api.options.KeysScanOptions;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import ru.vyarus.dropwizard.guice.module.installer.feature.eager.EagerSingleton;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Managed service that periodically syncs optimization logs from Redis to S3.
 * <p>
 * Uses Flux.interval() for scheduling instead of Quartz, similar to BaseRedisSubscriber.
 * Each optimization has its own distributed lock to prevent duplicate S3 uploads across instances.
 * <p>
 * Redis key pattern scanned: opik:logs:*:meta
 */
@Slf4j
@EagerSingleton
public class OptimizationLogFlusherJob implements Managed {

    // Pattern to extract workspace_id and optimization_id from meta key
    // opik:logs:{workspace_id}:{optimization_id}:meta
    private static final Pattern META_KEY_REGEX = Pattern.compile("opik:logs:([^:]+):([^:]+):meta");

    private final RedissonReactiveClient redisClient;
    private final OptimizationLogSyncService logSyncService;
    private final OptimizationLogsConfig config;

    private volatile Disposable subscription;
    private volatile Scheduler timerScheduler;

    @Inject
    public OptimizationLogFlusherJob(
            @NonNull RedissonReactiveClient redisClient,
            @NonNull OptimizationLogSyncService logSyncService,
            @NonNull @Config("optimizationLogs") OptimizationLogsConfig config) {
        this.redisClient = redisClient;
        this.logSyncService = logSyncService;
        this.config = config;
    }

    @Override
    public void start() {
        if (!config.isEnabled()) {
            log.info("Optimization log flusher is disabled");
            return;
        }

        if (timerScheduler == null) {
            timerScheduler = Schedulers.newSingle("optimization-log-flusher-timer", true);
        }

        if (subscription == null) {
            subscription = Flux.interval(config.getSyncInterval(), timerScheduler)
                    .onBackpressureDrop(tick -> log.debug("Backpressure drop, tick '{}'", tick))
                    .concatMap(tick -> scanAndSyncLogs())
                    .subscribe(
                            __ -> {
                            },
                            error -> log.error("Optimization log flusher failed", error));

            log.info("Optimization log flusher started with interval '{}'", config.getSyncInterval());
        }
    }

    @Override
    public void stop() {
        if (subscription != null && !subscription.isDisposed()) {
            subscription.dispose();
            log.info("Optimization log flusher stopped");
        }
        if (timerScheduler != null && !timerScheduler.isDisposed()) {
            timerScheduler.dispose();
        }
    }

    /**
     * Scan Redis for optimization log meta keys and sync each one.
     */
    private Mono<Void> scanAndSyncLogs() {
        var scanOptions = KeysScanOptions.defaults().pattern(OptimizationLogSyncService.META_KEY_PATTERN);
        return redisClient.getKeys().getKeys(scanOptions)
                .collectList()
                .flatMap(keys -> {
                    if (keys.isEmpty()) {
                        log.debug("No optimization log keys found");
                        return Mono.empty();
                    }

                    log.info("Found '{}' optimization log keys to check", keys.size());

                    return Flux.fromIterable(keys)
                            .flatMap(this::syncLogForMetaKey, config.getSyncConcurrency())
                            .onErrorContinue((error, key) -> log.warn("Failed to sync logs for key '{}'",
                                    key, error))
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
            log.warn("Invalid optimization ID in meta key '{}': '{}'", metaKey, optimizationIdStr);
            return Mono.empty();
        }
    }
}

package com.comet.opik.domain.optimization;

import com.comet.opik.domain.attachment.FileService;
import com.comet.opik.infrastructure.OptimizationLogsConfig;
import com.comet.opik.infrastructure.lock.LockService;
import com.google.inject.ImplementedBy;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RListReactive;
import org.redisson.api.RMapReactive;
import org.redisson.api.RedissonReactiveClient;
import org.redisson.client.codec.StringCodec;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;

/**
 * Service for synchronizing optimization logs from Redis to S3.
 * <p>
 * The Python backend writes logs to Redis during optimization execution.
 * This service is responsible for:
 * 1. Periodically syncing logs from Redis to S3 (called by scheduled job)
 * 2. Finalizing logs when optimization completes (on-demand)
 * <p>
 * Redis key structure (set by Python backend):
 * - Logs: opik:logs:{workspace_id}:{optimization_id} (LIST of raw log lines)
 * - Meta: opik:logs:{workspace_id}:{optimization_id}:meta (HASH with timestamps)
 * <p>
 * S3 key structure:
 * - logs/{workspace_id}/{optimization_id}.log
 */
@ImplementedBy(OptimizationLogSyncServiceImpl.class)
public interface OptimizationLogSyncService {

    /**
     * Redis key pattern for optimization log metadata.
     * Used by OptimizationLogFlusherJob to scan for active optimizations.
     */
    String META_KEY_PATTERN = "opik:logs:*:meta";

    /**
     * S3 key pattern for optimization logs.
     * Format: logs/optimization-studio/{workspace_id}/{optimization_id}.log.gz
     */
    String S3_KEY_PATTERN = "logs/optimization-studio/%s/%s.log.gz";

    /**
     * Formats the S3 key for an optimization's log file.
     *
     * @param workspaceId    the workspace ID
     * @param optimizationId the optimization ID
     * @return the S3 key path
     */
    static String formatS3Key(String workspaceId, UUID optimizationId) {
        return String.format(S3_KEY_PATTERN, workspaceId, optimizationId);
    }

    /**
     * Sync logs from Redis to S3 if there are new logs since last flush.
     * Uses distributed locking to prevent duplicate work across instances.
     *
     * @param workspaceId    the workspace ID
     * @param optimizationId the optimization ID
     * @return Mono that completes when sync is done (or skipped if no new logs)
     */
    Mono<Void> syncLogsToS3(@NonNull String workspaceId, @NonNull UUID optimizationId);

    /**
     * Finalize logs for a completed optimization.
     * This flushes any remaining logs from Redis to S3 and deletes the Redis keys.
     *
     * @param workspaceId    the workspace ID
     * @param optimizationId the optimization ID
     * @return Mono that completes when logs are finalized
     */
    Mono<Void> finalizeLogsOnCompletion(@NonNull String workspaceId, @NonNull UUID optimizationId);
}

@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Slf4j
class OptimizationLogSyncServiceImpl implements OptimizationLogSyncService {

    private static final String REDIS_LOG_KEY_PATTERN = "opik:logs:%s:%s";
    private static final String REDIS_META_KEY_PATTERN = "opik:logs:%s:%s:meta";
    private static final String LOCK_KEY_PATTERN = "opik:lock:logs:%s:%s";

    private static final String META_LAST_APPEND_TS = "last_append_ts";
    private static final String META_LAST_FLUSH_TS = "last_flush_ts";

    private static final String CONTENT_TYPE_GZIP = "application/gzip";

    /**
     * Record to hold log timestamp information for decision logging.
     */
    private record LogTimestamps(long lastAppend, long lastFlush) {
    }

    private final @NonNull RedissonReactiveClient redisClient;
    private final @NonNull FileService fileService;
    private final @NonNull LockService lockService;
    private final @NonNull @Config("optimizationLogs") OptimizationLogsConfig config;

    @Override
    @WithSpan
    public Mono<Void> syncLogsToS3(@NonNull String workspaceId, @NonNull UUID optimizationId) {
        if (!config.isEnabled()) {
            log.debug("Optimization log sync is disabled");
            return Mono.empty();
        }

        String logKey = formatLogKey(workspaceId, optimizationId);
        String metaKey = formatMetaKey(workspaceId, optimizationId);

        // First check if there are new logs (fast path, no lock needed)
        return getLogTimestamps(metaKey)
                .flatMap(timestamps -> {
                    long lastAppend = timestamps.lastAppend;
                    long lastFlush = timestamps.lastFlush;
                    boolean hasNew = lastAppend > lastFlush;

                    if (!hasNew) {
                        return Mono.empty();
                    }

                    // Acquire lock and perform sync
                    return executeWithLock(workspaceId, optimizationId,
                            doSyncToS3(workspaceId, optimizationId, logKey, metaKey, false));
                });
    }

    // TTL to set on Redis keys after finalization (1 hour) - allows late logs to be captured
    private static final long FINALIZATION_TTL_SECONDS = 3600;

    @Override
    @WithSpan
    public Mono<Void> finalizeLogsOnCompletion(@NonNull String workspaceId, @NonNull UUID optimizationId) {
        if (!config.isEnabled()) {
            log.debug("Optimization log sync is disabled");
            return Mono.empty();
        }

        log.info("Finalizing logs for optimization '{}' in workspace '{}'", optimizationId, workspaceId);

        String logKey = formatLogKey(workspaceId, optimizationId);
        String metaKey = formatMetaKey(workspaceId, optimizationId);

        // Sync logs to S3 and reduce TTL (don't delete - allow late logs to be captured)
        return executeWithLock(workspaceId, optimizationId,
                doSyncToS3AndReduceTTL(workspaceId, optimizationId, logKey, metaKey));
    }

    /**
     * Sync logs to S3 and reduce Redis key TTL to 1 hour.
     * We don't delete immediately to allow any late-arriving logs to be captured
     * by the periodic flusher job.
     */
    private Mono<Void> doSyncToS3AndReduceTTL(String workspaceId, UUID optimizationId,
            String logKey, String metaKey) {
        return doSyncToS3(workspaceId, optimizationId, logKey, metaKey, true);
    }

    /**
     * Reduce TTL on Redis keys to 1 hour after finalization.
     * This allows late-arriving logs to still be captured by the periodic flusher.
     */
    private Mono<Void> reduceRedisTTL(String logKey, String metaKey, UUID optimizationId) {
        return Mono.zip(
                redisClient.getKeys().expire(logKey, FINALIZATION_TTL_SECONDS, TimeUnit.SECONDS),
                redisClient.getKeys().expire(metaKey, FINALIZATION_TTL_SECONDS, TimeUnit.SECONDS))
                .doOnSuccess(__ -> log.info("Reduced TTL to 1 hour for optimization '{}' Redis keys", optimizationId))
                .then();
    }

    /**
     * Get log timestamps from meta to determine if sync is needed.
     * Uses HMGET to fetch both timestamps in a single Redis call.
     */
    private Mono<LogTimestamps> getLogTimestamps(String metaKey) {
        // Use StringCodec since Python stores plain text values
        RMapReactive<String, String> metaMap = redisClient.getMap(metaKey, StringCodec.INSTANCE);

        // Use getAll (HMGET) to fetch both timestamps in one Redis call
        return metaMap.getAll(Set.of(META_LAST_APPEND_TS, META_LAST_FLUSH_TS))
                .map(values -> {
                    long lastAppend = parseLong(values.getOrDefault(META_LAST_APPEND_TS, "0"));
                    long lastFlush = parseLong(values.getOrDefault(META_LAST_FLUSH_TS, "0"));
                    return new LogTimestamps(lastAppend, lastFlush);
                })
                .defaultIfEmpty(new LogTimestamps(0, 0));
    }

    /**
     * Perform the actual sync: read logs from Redis, upload to S3, update meta.
     *
     * @param isFinalize if true, reduce TTL on Redis keys after sync (for finalization)
     */
    private Mono<Void> doSyncToS3(String workspaceId, UUID optimizationId,
            String logKey, String metaKey, boolean isFinalize) {

        // Use StringCodec for log list and meta map since Python stores plain text values
        RListReactive<String> logList = redisClient.getList(logKey, StringCodec.INSTANCE);
        RMapReactive<String, String> metaMap = redisClient.getMap(metaKey, StringCodec.INSTANCE);

        return logList.readAll()
                .flatMap(logs -> {
                    if (logs == null || logs.isEmpty()) {
                        log.debug("No logs found in Redis for optimization '{}'", optimizationId);
                        return Mono.empty();
                    }

                    // Join all log lines with newlines
                    String logContent = String.join("\n", logs);
                    String s3Key = OptimizationLogSyncService.formatS3Key(workspaceId, optimizationId);

                    // Compress logs with gzip
                    byte[] compressedLogs = compressGzip(logContent);
                    log.info("Uploading '{}' log lines ({} bytes -> {} bytes gzipped) for optimization '{}' to S3",
                            logs.size(), logContent.length(), compressedLogs.length, optimizationId);

                    // Upload to S3 (blocking call wrapped in scheduler)
                    Mono<Void> uploadAndUpdate = Mono.fromCallable(() -> {
                        fileService.upload(s3Key, compressedLogs, CONTENT_TYPE_GZIP);
                        return true;
                    })
                            .subscribeOn(Schedulers.boundedElastic())
                            .then(updateLastFlushTimestamp(metaMap, optimizationId));

                    // If finalizing, also reduce TTL on Redis keys
                    if (isFinalize) {
                        return uploadAndUpdate.then(reduceRedisTTL(logKey, metaKey, optimizationId));
                    }
                    return uploadAndUpdate;
                });
    }

    /**
     * Update the last_flush_ts in meta after successful S3 upload.
     */
    private Mono<Void> updateLastFlushTimestamp(RMapReactive<String, String> metaMap, UUID optimizationId) {
        String now = String.valueOf(System.currentTimeMillis());
        return metaMap.put(META_LAST_FLUSH_TS, now)
                .doOnSuccess(__ -> log.debug("Updated last_flush_ts for optimization '{}'", optimizationId))
                .then();
    }

    /**
     * Execute action with distributed lock.
     */
    private Mono<Void> executeWithLock(String workspaceId, UUID optimizationId, Mono<Void> action) {
        String lockKey = formatLockKey(workspaceId, optimizationId);
        Duration lockDuration = config.getLockTimeout();

        return lockService.lockUsingToken(new LockService.Lock(lockKey), lockDuration)
                .flatMap(acquired -> {
                    if (!acquired) {
                        log.debug("Could not acquire lock for optimization '{}', another instance is syncing",
                                optimizationId);
                        return Mono.empty();
                    }

                    return action
                            .doFinally(__ -> lockService.unlockUsingToken(new LockService.Lock(lockKey)).subscribe());
                });
    }

    private static String formatLogKey(String workspaceId, UUID optimizationId) {
        return String.format(REDIS_LOG_KEY_PATTERN, workspaceId, optimizationId);
    }

    private static String formatMetaKey(String workspaceId, UUID optimizationId) {
        return String.format(REDIS_META_KEY_PATTERN, workspaceId, optimizationId);
    }

    private static String formatLockKey(String workspaceId, UUID optimizationId) {
        return String.format(LOCK_KEY_PATTERN, workspaceId, optimizationId);
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static byte[] compressGzip(String content) {
        try (var baos = new ByteArrayOutputStream();
                var gzip = new GZIPOutputStream(baos)) {
            gzip.write(content.getBytes(StandardCharsets.UTF_8));
            gzip.finish();
            return baos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Failed to compress logs with gzip", e);
        }
    }
}

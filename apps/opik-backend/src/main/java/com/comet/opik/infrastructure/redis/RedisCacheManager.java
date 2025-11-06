package com.comet.opik.infrastructure.redis;

import com.comet.opik.infrastructure.cache.CacheManager;
import com.comet.opik.infrastructure.cache.CacheMetrics;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import io.micrometer.core.instrument.Timer;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.redisson.api.RedissonReactiveClient;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;

/**
 * Redis-based cache manager implementation with performance monitoring.
 * All cache operations are tracked using CacheMetrics for observability.
 *
 * @since 1.0.0
 * @see CacheMetrics for performance monitoring
 */
@Slf4j
@RequiredArgsConstructor
class RedisCacheManager implements CacheManager {

    private final @NonNull RedissonReactiveClient redisClient;
    private final @NonNull CacheMetrics cacheMetrics;

    public Mono<Boolean> evict(@NonNull String key, boolean usePatternMatching) {
        String cacheName = extractCacheName(key);
        if (usePatternMatching) {
            return redisClient.getKeys().deleteByPattern(key)
                    .doOnSuccess(count -> {
                        if (count > 0) {
                            cacheMetrics.recordEviction(cacheName);
                            log.debug("Evicted {} cache entries matching pattern: {}", count, key);
                        }
                    })
                    .map(count -> count > 0);
        }
        return redisClient.getBucket(key).delete()
                .doOnSuccess(deleted -> {
                    if (deleted) {
                        cacheMetrics.recordEviction(cacheName);
                        log.debug("Evicted cache key: {}", key);
                    }
                });
    }

    public Mono<Boolean> put(@NonNull String key, @NonNull Object value, @NonNull Duration ttlDuration) {
        String cacheName = extractCacheName(key);
        Timer.Sample sample = cacheMetrics.startPutTimer();

        return Mono.fromCallable(() -> JsonUtils.writeValueAsString(value))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(json -> redisClient.getBucket(key).set(json))
                .then(Mono.defer(() -> redisClient.getBucket(key).expire(ttlDuration)))
                .doOnSuccess(result -> {
                    cacheMetrics.recordPut(cacheName);
                    cacheMetrics.recordPutDuration(sample, cacheName);
                    log.debug("Put value in cache: {} with TTL: {}", key, ttlDuration);
                })
                .doOnError(error -> {
                    log.error("Failed to put value in cache: {}", key, error);
                    cacheMetrics.recordPutDuration(sample, cacheName);
                });
    }

    public <T> Mono<T> get(@NonNull String key, @NonNull Class<T> clazz) {
        String cacheName = extractCacheName(key);
        Timer.Sample sample = cacheMetrics.startGetTimer();

        return redisClient.<String>getBucket(key)
                .get()
                .filter(StringUtils::isNotEmpty)
                .flatMap(json -> Mono.fromCallable(() -> JsonUtils.readValue(json, clazz))
                        .subscribeOn(Schedulers.boundedElastic()))
                .doOnSuccess(value -> {
                    if (value != null) {
                        cacheMetrics.recordHit(cacheName);
                        log.debug("Cache hit for key: {}", key);
                    } else {
                        cacheMetrics.recordMiss(cacheName);
                        log.debug("Cache miss for key: {}", key);
                    }
                    cacheMetrics.recordGetDuration(sample, cacheName);
                })
                .switchIfEmpty(Mono.defer(() -> {
                    cacheMetrics.recordMiss(cacheName);
                    cacheMetrics.recordGetDuration(sample, cacheName);
                    log.debug("Cache miss for key: {}", key);
                    return Mono.empty();
                }))
                .doOnError(error -> {
                    log.error("Error reading from cache: {}", key, error);
                    cacheMetrics.recordMiss(cacheName);
                    cacheMetrics.recordGetDuration(sample, cacheName);
                });
    }

    public <T> Mono<T> get(@NonNull String key, @NonNull TypeReference<T> clazz) {
        String cacheName = extractCacheName(key);
        Timer.Sample sample = cacheMetrics.startGetTimer();

        return redisClient.<String>getBucket(key)
                .get()
                .filter(StringUtils::isNotEmpty)
                .flatMap(json -> Mono.fromCallable(() -> JsonUtils.readValue(json, clazz))
                        .subscribeOn(Schedulers.boundedElastic()))
                .doOnSuccess(value -> {
                    if (value != null) {
                        cacheMetrics.recordHit(cacheName);
                        log.debug("Cache hit for key: {}", key);
                    } else {
                        cacheMetrics.recordMiss(cacheName);
                        log.debug("Cache miss for key: {}", key);
                    }
                    cacheMetrics.recordGetDuration(sample, cacheName);
                })
                .switchIfEmpty(Mono.defer(() -> {
                    cacheMetrics.recordMiss(cacheName);
                    cacheMetrics.recordGetDuration(sample, cacheName);
                    log.debug("Cache miss for key: {}", key);
                    return Mono.empty();
                }))
                .doOnError(error -> {
                    log.error("Error reading from cache: {}", key, error);
                    cacheMetrics.recordMiss(cacheName);
                    cacheMetrics.recordGetDuration(sample, cacheName);
                });
    }

    public Mono<Boolean> contains(@NonNull String key) {
        return redisClient.getBucket(key).isExists();
    }

    /**
     * Extract cache name from cache key for metrics tagging.
     * Cache keys are typically in format: "cacheName:key" or just "key"
     */
    private String extractCacheName(String key) {
        int colonIndex = key.indexOf(':');
        return colonIndex > 0 ? key.substring(0, colonIndex) : "default";
    }
}

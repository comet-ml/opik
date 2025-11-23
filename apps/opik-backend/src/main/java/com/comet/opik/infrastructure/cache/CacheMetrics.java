package com.comet.opik.infrastructure.cache;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Cache performance metrics collector using Micrometer.
 * Tracks cache hits, misses, hit ratios, and operation durations.
 *
 * @since 1.9.0
 */
@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class CacheMetrics {

    private final MeterRegistry meterRegistry;

    // Track cache statistics per cache name
    private final Map<String, CacheStats> cacheStatsMap = new ConcurrentHashMap<>();

    /**
     * Record a cache hit for the specified cache
     */
    public void recordHit(@NonNull String cacheName) {
        getOrCreateStats(cacheName).hits.increment();
        Counter.builder("opik.cache.operations")
                .tag("cache", cacheName)
                .tag("result", "hit")
                .description("Cache hit count")
                .register(meterRegistry)
                .increment();
    }

    /**
     * Record a cache miss for the specified cache
     */
    public void recordMiss(@NonNull String cacheName) {
        getOrCreateStats(cacheName).misses.increment();
        Counter.builder("opik.cache.operations")
                .tag("cache", cacheName)
                .tag("result", "miss")
                .description("Cache miss count")
                .register(meterRegistry)
                .increment();
    }

    /**
     * Record a cache eviction
     */
    public void recordEviction(@NonNull String cacheName) {
        getOrCreateStats(cacheName).evictions.increment();
        Counter.builder("opik.cache.evictions")
                .tag("cache", cacheName)
                .description("Cache eviction count")
                .register(meterRegistry)
                .increment();
    }

    /**
     * Record a cache put operation
     */
    public void recordPut(@NonNull String cacheName) {
        getOrCreateStats(cacheName).puts.increment();
        Counter.builder("opik.cache.puts")
                .tag("cache", cacheName)
                .description("Cache put operation count")
                .register(meterRegistry)
                .increment();
    }

    /**
     * Start timing a cache get operation
     */
    public Timer.Sample startGetTimer() {
        return Timer.start(meterRegistry);
    }

    /**
     * Record the duration of a cache get operation
     */
    public void recordGetDuration(@NonNull Timer.Sample sample, @NonNull String cacheName) {
        sample.stop(Timer.builder("opik.cache.get.duration")
                .tag("cache", cacheName)
                .description("Duration of cache get operations")
                .register(meterRegistry));
    }

    /**
     * Start timing a cache put operation
     */
    public Timer.Sample startPutTimer() {
        return Timer.start(meterRegistry);
    }

    /**
     * Record the duration of a cache put operation
     */
    public void recordPutDuration(@NonNull Timer.Sample sample, @NonNull String cacheName) {
        sample.stop(Timer.builder("opik.cache.put.duration")
                .tag("cache", cacheName)
                .description("Duration of cache put operations")
                .register(meterRegistry));
    }

    /**
     * Get the current hit ratio for a cache (hits / (hits + misses))
     */
    public double getHitRatio(@NonNull String cacheName) {
        CacheStats stats = cacheStatsMap.get(cacheName);
        if (stats == null) {
            return 0.0;
        }

        long hits = stats.hits.get();
        long misses = stats.misses.get();
        long total = hits + misses;

        return total == 0 ? 0.0 : (double) hits / total;
    }

    /**
     * Get cache statistics for a specific cache
     */
    public CacheStatistics getStatistics(@NonNull String cacheName) {
        CacheStats stats = cacheStatsMap.get(cacheName);
        if (stats == null) {
            return new CacheStatistics(0, 0, 0, 0, 0.0);
        }

        long hits = stats.hits.get();
        long misses = stats.misses.get();
        long puts = stats.puts.get();
        long evictions = stats.evictions.get();
        long total = hits + misses;
        double hitRatio = total == 0 ? 0.0 : (double) hits / total;

        return new CacheStatistics(hits, misses, puts, evictions, hitRatio);
    }

    /**
     * Log cache statistics for monitoring
     */
    public void logStatistics(@NonNull String cacheName) {
        CacheStatistics stats = getStatistics(cacheName);
        log.info("Cache statistics for '{}': hits={}, misses={}, puts={}, evictions={}, hitRatio={:.2f}%",
                cacheName, stats.hits, stats.misses, stats.puts, stats.evictions, stats.hitRatio * 100);
    }

    /**
     * Reset statistics for a cache
     */
    public void resetStatistics(@NonNull String cacheName) {
        cacheStatsMap.remove(cacheName);
        log.debug("Reset statistics for cache: {}", cacheName);
    }

    private CacheStats getOrCreateStats(String cacheName) {
        return cacheStatsMap.computeIfAbsent(cacheName, key -> {
            CacheStats stats = new CacheStats();

            // Register gauges for real-time monitoring
            Gauge.builder("opik.cache.hit.ratio", stats, s -> {
                        long hits = s.hits.get();
                        long misses = s.misses.get();
                        long total = hits + misses;
                        return total == 0 ? 0.0 : (double) hits / total;
                    })
                    .tag("cache", cacheName)
                    .description("Cache hit ratio (hits / total requests)")
                    .register(meterRegistry);

            Gauge.builder("opik.cache.size.hits", stats, s -> s.hits.get())
                    .tag("cache", cacheName)
                    .description("Total cache hits")
                    .register(meterRegistry);

            Gauge.builder("opik.cache.size.misses", stats, s -> s.misses.get())
                    .tag("cache", cacheName)
                    .description("Total cache misses")
                    .register(meterRegistry);

            return stats;
        });
    }

    /**
     * Internal cache statistics holder
     */
    private static class CacheStats {
        final AtomicLong hits = new AtomicLong(0);
        final AtomicLong misses = new AtomicLong(0);
        final AtomicLong puts = new AtomicLong(0);
        final AtomicLong evictions = new AtomicLong(0);
    }

    /**
     * Immutable cache statistics snapshot
     */
    public record CacheStatistics(
            long hits,
            long misses,
            long puts,
            long evictions,
            double hitRatio
    ) {}
}

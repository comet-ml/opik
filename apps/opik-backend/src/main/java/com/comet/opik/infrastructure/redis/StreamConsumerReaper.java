package com.comet.opik.infrastructure.redis;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongCounter;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RStreamReactive;
import org.redisson.api.RedissonReactiveClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Collection;
import java.util.Set;

/**
 * Removes orphaned Redis stream consumers — those left behind when a backend process exits non-gracefully
 * (OOMKill, SIGKILL, crash, node eviction) and never runs {@code XGROUP DELCONSUMER} (OPIK-6982).
 * <p>
 * For each stream it discovers groups via {@code XINFO GROUPS} (scoped to the stream — no keyspace scan) and,
 * within each group, deletes consumers that are <b>both</b> idle beyond the threshold <b>and</b> have no pending
 * entries. The {@code pending == 0} guard is the key safety property: a crashed consumer's pending entries are
 * reclaimed by {@code XAUTOCLAIM} into a live consumer first; only once its PEL is empty is it removed. This means
 * the reaper never destroys un-acked work (which {@code XGROUP DELCONSUMER} on a consumer with pending entries
 * would do). Idle time is server-side, so a live consumer (idle ~ seconds) is never a candidate, regardless of
 * which instance runs the reaper.
 */
@Slf4j
@Singleton
public class StreamConsumerReaper {

    private static final AttributeKey<String> STREAM_KEY = AttributeKey.stringKey("stream");
    private static final AttributeKey<String> GROUP_KEY = AttributeKey.stringKey("consumer_group");

    private final RedissonReactiveClient redisson;
    private final LongCounter reapedConsumers;
    private final LongCounter reaperErrors;

    @Inject
    public StreamConsumerReaper(@NonNull RedissonReactiveClient redisson) {
        this.redisson = redisson;
        var meter = GlobalOpenTelemetry.getMeter("opik.redis");
        this.reapedConsumers = meter
                .counterBuilder("opik_redis_stream_reaped_consumers")
                .setDescription("Orphaned (idle beyond threshold, no pending) stream consumers removed by the reaper")
                .build();
        this.reaperErrors = meter
                .counterBuilder("opik_redis_stream_reaper_errors")
                .setDescription("Errors while reaping orphaned stream consumers")
                .build();
    }

    /**
     * Reaps orphaned consumers across the given streams.
     *
     * @return the total number of consumers removed
     */
    public Mono<Long> reap(@NonNull Collection<String> streamNames, @NonNull Duration idleThreshold) {
        long idleThresholdMillis = idleThreshold.toMillis();
        // De-duplicate: online-scoring streams are distinct keys but multiple subscribers may report the same name.
        return Flux.fromIterable(Set.copyOf(streamNames))
                .flatMap(streamName -> reapStream(streamName, idleThresholdMillis))
                .reduce(0L, Long::sum);
    }

    private Mono<Long> reapStream(String streamName, long idleThresholdMillis) {
        RStreamReactive<Object, Object> stream = redisson.getStream(streamName);
        return stream.listGroups()
                .flatMapMany(Flux::fromIterable)
                .flatMap(group -> reapGroup(stream, streamName, group.getName(), idleThresholdMillis))
                .reduce(0L, Long::sum)
                .onErrorResume(throwable -> {
                    // The stream may not exist yet (never created) — XINFO GROUPS returns "ERR no such key".
                    // Nothing to reap; log at debug and move on.
                    log.debug("Skipping reaper for stream '{}': '{}'", streamName, throwable.getMessage());
                    return Mono.just(0L);
                });
    }

    private Mono<Long> reapGroup(RStreamReactive<Object, Object> stream, String streamName, String group,
            long idleThresholdMillis) {
        return stream.listConsumers(group)
                .flatMapMany(Flux::fromIterable)
                .filter(consumer -> consumer.getIdleTime() > idleThresholdMillis && consumer.getPending() == 0)
                .flatMap(consumer -> stream.removeConsumer(group, consumer.getName())
                        .doOnSuccess(pending -> {
                            reapedConsumers.add(1, Attributes.of(STREAM_KEY, streamName, GROUP_KEY, group));
                            log.info("Reaped orphaned consumer '{}' from group '{}' on stream '{}', idle '{}' ms",
                                    consumer.getName(), group, streamName, consumer.getIdleTime());
                        })
                        .thenReturn(1L)
                        .onErrorResume(throwable -> {
                            reaperErrors.add(1, Attributes.of(STREAM_KEY, streamName, GROUP_KEY, group));
                            log.warn("Failed to reap consumer '{}' from group '{}' on stream '{}'",
                                    consumer.getName(), group, streamName, throwable);
                            return Mono.just(0L);
                        }))
                .reduce(0L, Long::sum)
                .onErrorResume(throwable -> {
                    reaperErrors.add(1, Attributes.of(STREAM_KEY, streamName, GROUP_KEY, group));
                    log.warn("Failed to list consumers for group '{}' on stream '{}'", group, streamName, throwable);
                    return Mono.just(0L);
                });
    }
}

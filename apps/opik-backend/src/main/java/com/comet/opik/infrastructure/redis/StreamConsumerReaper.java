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
import java.util.LinkedHashSet;
import java.util.Objects;

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
    private static final AttributeKey<String> RESULT_KEY = AttributeKey.stringKey("result");
    private static final String RESULT_SUCCESS = "success";
    private static final String RESULT_ERROR = "error";

    // When XINFO GROUPS targets a stream key that was never created, Redisson raises a generic
    // org.redisson.client.RedisException whose message is "ERR no such key. channel: ... command: (XINFO GROUPS)..."
    // There is no typed exception for this, so we match the stable Redis error token below.
    private static final String NO_SUCH_KEY = "no such key";

    private final RedissonReactiveClient redisson;
    private final LongCounter reaperConsumers;

    @Inject
    public StreamConsumerReaper(@NonNull RedissonReactiveClient redisson) {
        this.redisson = redisson;
        var meter = GlobalOpenTelemetry.getMeter("opik.redis");
        this.reaperConsumers = meter
                .counterBuilder("opik_redis_stream_reaper_consumers")
                .setDescription(
                        "Stream consumer reaper outcomes, tagged by result: 'success' (orphan removed) or 'error'")
                .build();
    }

    /**
     * Reaps orphaned consumers across the given streams.
     *
     * @return the total number of consumers removed
     */
    public Mono<Long> reap(Collection<String> streamNames, @NonNull Duration idleThreshold) {
        if (streamNames == null || streamNames.isEmpty()) {
            return Mono.just(0L);
        }
        long idleThresholdMillis = idleThreshold.toMillis();
        // De-duplicate while preserving order: online-scoring streams are distinct keys but multiple subscribers may
        // report the same name.
        return Flux.fromIterable(new LinkedHashSet<>(streamNames))
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
                    if (isNoSuchKey(throwable)) {
                        // The stream hasn't been created yet (no producer/consumer activity) — nothing to reap.
                        log.info("Skipping reaper for stream '{}', stream may not exist", streamName, throwable);
                    } else {
                        // ACL/connection/timeout/etc.: surface it (metric + warn) instead of silently skipping,
                        // but still return 0 so the other streams are reaped.
                        reaperConsumers.add(1, Attributes.of(STREAM_KEY, streamName, RESULT_KEY, RESULT_ERROR));
                        log.warn("Failed to reap orphaned consumers for stream '{}'", streamName, throwable);
                    }
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
                            reaperConsumers.add(1,
                                    Attributes.of(STREAM_KEY, streamName, GROUP_KEY, group, RESULT_KEY,
                                            RESULT_SUCCESS));
                            log.info("Reaped orphaned consumer '{}' from group '{}' on stream '{}', idle '{}' ms",
                                    consumer.getName(), group, streamName, consumer.getIdleTime());
                        })
                        .thenReturn(1L)
                        .onErrorResume(throwable -> {
                            reaperConsumers.add(1,
                                    Attributes.of(STREAM_KEY, streamName, GROUP_KEY, group, RESULT_KEY, RESULT_ERROR));
                            log.warn("Failed to reap consumer '{}' from group '{}' on stream '{}'",
                                    consumer.getName(), group, streamName, throwable);
                            return Mono.just(0L);
                        }))
                .reduce(0L, Long::sum)
                .onErrorResume(throwable -> {
                    reaperConsumers.add(1,
                            Attributes.of(STREAM_KEY, streamName, GROUP_KEY, group, RESULT_KEY, RESULT_ERROR));
                    log.warn("Failed to list consumers for group '{}' on stream '{}'", group, streamName, throwable);
                    return Mono.just(0L);
                });
    }

    /**
     * Best-effort classification of the "stream key doesn't exist yet" case. Confirmed against a real Redis:
     * Redisson raises a generic {@code org.redisson.client.RedisException} with message {@code "ERR no such key..."}
     * — there is no typed exception to catch, so this is a substring match (mirroring the existing
     * {@code NOGROUP}/{@code BUSYGROUP} handling in {@code BaseRedisSubscriber}) and could break on a Redis/Redisson
     * version bump. The downside is bounded — a misclassification only changes the log level/metric of a best-effort
     * cleanup job; correctness (which consumers get reaped) is unaffected.
     */
    private static boolean isNoSuchKey(Throwable throwable) {
        return Objects.toString(throwable.getMessage(), "").contains(NO_SUCH_KEY);
    }
}

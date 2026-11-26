package com.comet.opik.domain.topics;

import com.comet.opik.infrastructure.TopicsConfig;
import com.comet.opik.infrastructure.lock.LockService;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonReactiveClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The pace of a workspace's long read that did not finish: a Redis key per workspace, set for 1.5 tick intervals from
 * that tick's start. While it is present, the workspace is due by {@code long_window_read_at} but takes its fresh read,
 * so one whose long read fails every time still gets a fresh read every other tick. A long read that finishes sets no
 * key.
 */
@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class LongReadRetry {

    static final String KEY_PREFIX = "topics:vectors:long_read_retry:";

    private final @NonNull @Config("topics") TopicsConfig config;
    private final @NonNull LockService lockService;
    private final @NonNull RedissonReactiveClient redis;

    /**
     * Of the workspaces due by the column, those whose long read waits for a fresh read: one read for all of them, none
     * when no workspace is due. Redis unavailable: all of them, so the tick runs the short window, as when the
     * cluster-wide key could not be read.
     */
    Mono<Set<String>> waiting(@NonNull Collection<String> dueWorkspaceIds) {
        if (dueWorkspaceIds.isEmpty()) {
            return Mono.just(Set.of());
        }
        return redis.getBuckets().get(dueWorkspaceIds.stream().map(LongReadRetry::key).toArray(String[]::new))
                .map(present -> dueWorkspaceIds.stream().filter(id -> present.containsKey(key(id)))
                        .collect(Collectors.toUnmodifiableSet()))
                .defaultIfEmpty(Set.of())
                .onErrorResume(exception -> {
                    log.warn("Topic vectors long-read retry keys not read, running the short window, workspaces '{}'",
                            dueWorkspaceIds.size(), exception);
                    return Mono.just(Set.copyOf(dueWorkspaceIds));
                });
    }

    /**
     * Sets the key of each workspace whose long read did not finish, until 1.5 tick intervals after {@code start}, so
     * the pace does not depend on how long the tick ran; a tick that ends past that sets none. Redis unavailable: no
     * key, and the workspace takes its long read again next tick.
     */
    Mono<Void> notFinished(@NonNull Collection<String> workspaceIds, @NonNull Instant start) {
        var vectors = config.getVectors();
        var until = start.plus(vectors.getTickInterval().toJavaDuration().multipliedBy(3).dividedBy(2));
        return Flux.fromIterable(workspaceIds)
                .flatMap(workspaceId -> Mono.defer(() -> {
                    var hold = Duration.between(Clock.systemUTC().instant(), until);
                    // Redis takes a TTL in whole milliseconds, above zero.
                    return hold.toMillis() > 0
                            ? lockService.lockUsingToken(new LockService.Lock(key(workspaceId)), hold).then()
                            : Mono.<Void>empty();
                }).onErrorResume(exception -> {
                    log.warn("Topic vectors long-read retry key not set, reading the long window again next tick, "
                            + "workspace '{}'", workspaceId, exception);
                    return Mono.empty();
                }), vectors.getEmbedConcurrency())
                .then();
    }

    static String key(String workspaceId) {
        return KEY_PREFIX + workspaceId;
    }
}

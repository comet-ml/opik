package com.comet.opik.domain;

import com.comet.opik.api.AnnotationQueue;
import com.comet.opik.infrastructure.AnnotationQueueRoutingConfig;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.redisson.api.RScoredSortedSetReactive;
import org.redisson.api.RedissonReactiveClient;
import org.redisson.client.codec.StringCodec;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The Redis buffer between score events and the routing stream (OPIK-6303).
 *
 * <p>One ZSET, {@link AnnotationQueueRoutingConfig#PENDING_SET_KEY}. A member is one
 * (workspace, scope, entity), scored by the time of its latest write: a later score on the same entity
 * re-scores the member rather than adding one, which is the deduplication, and pushes its due time out, so
 * an entity is flushed only once {@code debounceDelay} has passed since its last score. The consumer
 * therefore never reads a score younger than that — the floor that keeps it clear of ClickHouse replica
 * lag. The flush groups due members by (workspace, scope) and publishes one stream message per group, then
 * removes them. Publish before remove, so a crash in between costs a duplicate message rather than a lost
 * one; the consumer is idempotent.
 *
 * <p>Same shape as {@code ExperimentAggregationPublisher} and {@code ProjectLastUpdatedTraceBufferService}.
 * Everything a member needs is in the member, so there is no second key to expire or to go missing, and the
 * key's own TTL bounds it: writers set the TTL only when the key has none and every flush run renews it, so
 * a buffer nothing drains expires {@code bufferTtl} after the last flush instead of growing without limit.
 */
@Slf4j
@Singleton
public class AnnotationQueueRoutingBufferService {

    // workspaceId:scope:entityId, as the other Redis buffers key their members. Parsed from the right,
    // since the entity id is a UUID and the scope a fixed enum value, neither of which contains the separator.
    private static final String MEMBER_FORMAT = "%s:%s:%s";
    private static final String MEMBER_SEPARATOR = ":";

    private final @NonNull RedissonReactiveClient redisson;
    private final @NonNull AnnotationQueueRoutingPublisher publisher;
    private final @NonNull AnnotationQueueRoutingConfig config;

    @Inject
    public AnnotationQueueRoutingBufferService(@NonNull RedissonReactiveClient redisson,
            @NonNull AnnotationQueueRoutingPublisher publisher,
            @NonNull @Config("annotationQueueRouting") AnnotationQueueRoutingConfig config) {
        this.redisson = redisson;
        this.publisher = publisher;
        this.config = config;
    }

    public Mono<Void> add(@NonNull String workspaceId, @NonNull AnnotationQueue.AnnotationScope scope,
            Set<UUID> entityIds) {

        if (CollectionUtils.isEmpty(entityIds)) {
            return Mono.empty();
        }

        double now = Instant.now().toEpochMilli();
        Map<String, Double> members = entityIds.stream()
                .collect(Collectors.toMap(entityId -> member(workspaceId, scope, entityId), __ -> now));

        return Mono.defer(() -> {
            var pending = pending();
            return pending.addAll(members)
                    .doOnNext(added -> {
                        int folded = members.size() - added;
                        if (folded > 0) {
                            AnnotationQueueRoutingMetrics.SCORES_DEDUPLICATED.add(folded);
                        }
                    })
                    .then(pending.expireIfNotSet(config.getBufferTtl().toJavaDuration()))
                    .doOnError(error -> log.error(
                            "Failed to buffer '{}' entities for annotation queue routing, scope '{}', workspace '{}'",
                            members.size(), scope, workspaceId, error))
                    .then();
        });
    }

    /**
     * Publishes every member whose last write is older than {@code debounceDelay}, a page at a time, and returns how many stream
     * messages that took. Each page is grouped in memory — bounded by {@code jobBatchSize} — and each group
     * is removed as soon as its message is on the stream, so a run cut short by the job's time budget
     * leaves nothing half-done for the next one to redo.
     */
    public Mono<Long> flush() {
        return Mono.defer(() -> {
            var pending = pending();
            double cutoff = Instant.now().minusMillis(config.getDebounceDelay().toMilliseconds()).toEpochMilli();
            return drain(pending, cutoff, 0L)
                    .flatMap(published -> pending.expire(config.getBufferTtl().toJavaDuration())
                            .thenReturn(published));
        });
    }

    private Mono<Long> drain(RScoredSortedSetReactive<String> pending, double cutoff, long publishedSoFar) {
        int pageSize = config.getJobBatchSize();
        // Redisson answers an empty range with an empty Mono rather than an empty collection.
        return pending.valueRange(Double.NEGATIVE_INFINITY, true, cutoff, true, 0, pageSize)
                .defaultIfEmpty(List.of())
                .flatMap(page -> {
                    if (page.isEmpty()) {
                        return Mono.just(publishedSoFar);
                    }
                    return publishPage(pending, page)
                            .flatMap(published -> page.size() < pageSize
                                    ? Mono.just(publishedSoFar + published)
                                    : drain(pending, cutoff, publishedSoFar + published));
                });
    }

    private Mono<Long> publishPage(RScoredSortedSetReactive<String> pending, Collection<String> page) {
        Map<GroupKey, Group> groups = new HashMap<>();
        List<String> malformed = new ArrayList<>();
        for (String member : page) {
            PendingEntity entity = decode(member);
            if (entity == null) {
                malformed.add(member);
                continue;
            }
            groups.computeIfAbsent(GroupKey.of(entity), __ -> new Group()).add(member, entity.entityId());
        }

        // Removed rather than left to be re-read every run forever; nothing can be routed from them anyway.
        Mono<Void> dropMalformed = malformed.isEmpty()
                ? Mono.empty()
                : Mono.fromRunnable(() -> log.warn(
                        "Dropping '{}' malformed annotation queue routing buffer members", malformed.size()))
                        .then(pending.removeAll(malformed))
                        .then();

        return dropMalformed
                .thenMany(Flux.fromIterable(groups.entrySet()))
                .concatMap(entry -> publisher
                        .enqueue(entry.getKey().workspaceId(), entry.getKey().scope(), entry.getValue().entityIds())
                        .then(pending.removeAll(entry.getValue().members()))
                        .thenReturn(1L))
                .reduce(0L, Long::sum)
                .doOnNext(published -> {
                    if (published > 0) {
                        AnnotationQueueRoutingMetrics.MESSAGES_FLUSHED.add(published);
                    }
                });
    }

    private RScoredSortedSetReactive<String> pending() {
        return redisson.getScoredSortedSet(AnnotationQueueRoutingConfig.PENDING_SET_KEY, StringCodec.INSTANCE);
    }

    private static String member(String workspaceId, AnnotationQueue.AnnotationScope scope, UUID entityId) {
        return MEMBER_FORMAT.formatted(workspaceId, scope.getValue(), entityId);
    }

    private static PendingEntity decode(String member) {
        int entityAt = member.lastIndexOf(MEMBER_SEPARATOR);
        int scopeAt = entityAt > 0 ? member.lastIndexOf(MEMBER_SEPARATOR, entityAt - 1) : -1;
        if (scopeAt <= 0) {
            log.warn("Malformed annotation queue routing buffer member: '{}'", member);
            return null;
        }
        try {
            return new PendingEntity(
                    member.substring(0, scopeAt),
                    AnnotationQueue.AnnotationScope.fromString(member.substring(scopeAt + 1, entityAt)),
                    UUID.fromString(member.substring(entityAt + 1)));
        } catch (IllegalArgumentException e) {
            log.warn("Malformed annotation queue routing buffer member: '{}'", member, e);
            return null;
        }
    }

    private record PendingEntity(String workspaceId, AnnotationQueue.AnnotationScope scope, UUID entityId) {
    }

    private record GroupKey(String workspaceId, AnnotationQueue.AnnotationScope scope) {
        static GroupKey of(PendingEntity entity) {
            return new GroupKey(entity.workspaceId(), entity.scope());
        }
    }

    private static final class Group {
        private final List<String> members = new ArrayList<>();
        private final Set<UUID> entityIds = new HashSet<>();

        void add(String member, UUID entityId) {
            members.add(member);
            entityIds.add(entityId);
        }

        List<String> members() {
            return members;
        }

        Set<UUID> entityIds() {
            return Set.copyOf(entityIds);
        }
    }
}

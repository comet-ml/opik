package com.comet.opik.domain;

import com.comet.opik.api.AnnotationQueue;
import com.comet.opik.infrastructure.AnnotationQueueRoutingConfig;
import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.Builder;
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
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
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
            // GT: a member's timestamp only ever moves forward, so a write that lands out of order — or from a
            // replica with a slower clock — cannot pull a fresher entity back within reach of the next flush.
            return pending.addAllIfGreater(members)
                    .then(pending.expireIfNotSet(config.getBufferTtl().toJavaDuration()))
                    .doOnError(error -> log.error(
                            "Failed to buffer entities for annotation queue routing, size '{}', scope '{}', workspace '{}'",
                            members.size(), scope, workspaceId, error))
                    .then();
        });
    }

    /**
     * Publishes every member whose last write is older than {@code debounceDelay}, a page at a time, and
     * returns how many stream messages it published. Each page is grouped in memory — bounded by {@code jobBatchSize} — and each group
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

    /**
     * Groups one page and publishes a message per group. Everything here is derived from the page with
     * streams into immutable values: the page is parsed once, malformed members are separated from the rest
     * and each group carries its own members and entity ids. Nothing is shared or mutated, so the grouping
     * holds whether the page is handled on the job's thread or a Reactor worker.
     */
    private Mono<Long> publishPage(RScoredSortedSetReactive<String> pending, Collection<String> page) {
        var parsed = page.stream().map(ParsedMember::of).toList();

        List<String> malformed = parsed.stream()
                .filter(ParsedMember::isMalformed)
                .map(ParsedMember::member)
                .toList();

        Map<GroupKey, PendingGroup> groups = parsed.stream()
                .filter(Predicate.not(ParsedMember::isMalformed))
                .collect(Collectors.groupingBy(member -> GroupKey.of(member.entity()),
                        Collectors.collectingAndThen(Collectors.toList(), PendingGroup::of)));

        // Removed rather than left to be re-read every run forever; nothing can be routed from them anyway.
        Mono<Void> dropMalformed = malformed.isEmpty()
                ? Mono.empty()
                : Mono.fromRunnable(() -> log.warn(
                        "Dropping malformed members from the annotation queue routing buffer, size '{}'",
                        malformed.size()))
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

    /**
     * One buffer member as read back: the raw member, and what it decodes to. A {@code null} entity is a
     * member this deployment cannot parse — a leftover from an older member format, or a corrupted write.
     */
    @Builder(toBuilder = true)
    private record ParsedMember(@NonNull String member, @Nullable PendingEntity entity) {

        static ParsedMember of(@NonNull String member) {
            return ParsedMember.builder().member(member).entity(decode(member)).build();
        }

        boolean isMalformed() {
            return entity == null;
        }

        private static @Nullable PendingEntity decode(String member) {
            int entityAt = member.lastIndexOf(MEMBER_SEPARATOR);
            int scopeAt = entityAt > 0 ? member.lastIndexOf(MEMBER_SEPARATOR, entityAt - 1) : -1;
            if (scopeAt <= 0) {
                log.warn("Malformed annotation queue routing buffer member, member '{}'", member);
                return null;
            }
            try {
                return PendingEntity.builder()
                        .workspaceId(member.substring(0, scopeAt))
                        .scope(AnnotationQueue.AnnotationScope.fromString(member.substring(scopeAt + 1, entityAt)))
                        .entityId(UUID.fromString(member.substring(entityAt + 1)))
                        .build();
            } catch (IllegalArgumentException exception) {
                log.warn("Malformed annotation queue routing buffer member, member '{}'", member, exception);
                return null;
            }
        }
    }

    @Builder(toBuilder = true)
    private record PendingEntity(@NonNull String workspaceId, @NonNull AnnotationQueue.AnnotationScope scope,
            @NonNull UUID entityId) {
    }

    /** What one stream message is addressed to: everything in a group travels together. */
    @Builder(toBuilder = true)
    private record GroupKey(@NonNull String workspaceId, @NonNull AnnotationQueue.AnnotationScope scope) {

        static GroupKey of(@NonNull PendingEntity entity) {
            return GroupKey.builder().workspaceId(entity.workspaceId()).scope(entity.scope()).build();
        }
    }

    /**
     * The members of one group and the entities they name. Both are kept: the entity ids are what the
     * message carries, the members are what is removed from the buffer once it is on the stream.
     */
    @Builder(toBuilder = true)
    private record PendingGroup(@NonNull List<String> members, @NonNull Set<UUID> entityIds) {

        static PendingGroup of(@NonNull List<ParsedMember> parsed) {
            return PendingGroup.builder()
                    .members(parsed.stream().map(ParsedMember::member).toList())
                    .entityIds(parsed.stream()
                            .map(member -> member.entity().entityId())
                            .collect(Collectors.toUnmodifiableSet()))
                    .build();
        }
    }
}

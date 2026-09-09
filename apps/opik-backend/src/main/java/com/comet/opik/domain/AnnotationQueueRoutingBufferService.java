package com.comet.opik.domain;

import com.comet.opik.api.AnnotationQueue;
import com.comet.opik.infrastructure.AnnotationQueueRoutingConfig;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.Builder;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonReactiveClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Owns the debouncing of routing work: the score-path write that records an entity as worth evaluating,
 * and the periodic flush that turns what is due into stream messages.
 *
 * <p>{@link #record} writes each entity to a Redis ZSET (member {@code "workspaceId:scope:entityId"},
 * score = the epoch millis at which it becomes due) via {@code addIfAbsent}; {@link #flush}, driven by
 * {@code AnnotationQueueRoutingFlushJob}, drains what is due onto the routing stream. Same shape as
 * {@link ProjectLastUpdatedTraceBufferService}, for the same reason: keep the ingestion path cheap and do
 * the real work once, later.
 *
 * <p>The ZSET is what makes repeated scoring cheap. Ten scores written to one trace in ten separate calls
 * used to be ten stream messages and ten full evaluations of that trace — the first adding the item and
 * the other nine each doing six database round trips to discover it was already there. As one member, the
 * evaluation happens once.
 *
 * <p>{@code addIfAbsent}, not {@code addIfGreater}: the deadline belongs to the entity's <em>first</em>
 * score and later scores must not push it out. Experiment denormalization resets its timer on every write,
 * which is right for a derived aggregate that only has to be correct eventually, but here it would let a
 * continuously scored trace never reach a reviewer.
 *
 * <p>The author travels in a companion hash rather than in the member, so that it takes no part in the
 * collapsing — two people scoring the same trace is still one evaluation. Last write wins, and the queue
 * item is attributed to whichever score was recorded last, which is the attribution that score would have
 * produced had it arrived alone.
 */
@Slf4j
@Singleton
public class AnnotationQueueRoutingBufferService {

    static final String PENDING_SET_KEY = "annotation-queue:routing:pending";
    static final String PENDING_AUTHORS_KEY = "annotation-queue:routing:pending-authors";
    static final String PENDING_SCORE_NAMES_KEY = "annotation-queue:routing:pending-score-names";

    private static final String MEMBER_SEPARATOR = ":";
    private static final String NAME_SEPARATOR = "\u001f";

    private final @NonNull RedissonReactiveClient redisClient;
    private final @NonNull AnnotationQueueRoutingConfig config;
    private final @NonNull AnnotationQueueRoutingPublisher publisher;

    @Inject
    public AnnotationQueueRoutingBufferService(@NonNull RedissonReactiveClient redisClient,
            @NonNull @Config("annotationQueueRouting") AnnotationQueueRoutingConfig config,
            @NonNull AnnotationQueueRoutingPublisher publisher) {
        this.redisClient = redisClient;
        this.config = config;
        this.publisher = publisher;
    }

    /** An entity whose debounce window has elapsed, as read back out of the buffer. */
    @Builder(toBuilder = true)
    private record DueEntity(
            @NonNull String workspaceId,
            @NonNull AnnotationQueue.AnnotationScope scope,
            @NonNull UUID entityId,
            String userName,
            @NonNull Set<String> scoreNames) {
    }

    /** One published message: the entities of a single workspace, scope and author. */
    private record MessageGroup(String workspaceId, String userName,
            AnnotationQueue.AnnotationScope scope, Set<UUID> entityIds,
            Map<UUID, Set<String>> scoreNamesByEntity) {
    }

    public Mono<Void> record(@NonNull String workspaceId, String userName,
            @NonNull AnnotationQueue.AnnotationScope scope, @NonNull Set<UUID> entityIds,
            @NonNull Set<String> scoreNames) {

        if (!config.isEnabled() || entityIds.isEmpty()) {
            return Mono.empty();
        }

        long dueAt = Instant.now().plusMillis(config.getDebounceDelay().toMilliseconds()).toEpochMilli();
        var pending = redisClient.getScoredSortedSet(PENDING_SET_KEY);
        var authors = redisClient.<String, String>getMap(PENDING_AUTHORS_KEY);
        var pendingScoreNames = redisClient.<String, String>getMap(PENDING_SCORE_NAMES_KEY);
        String encodedNames = String.join(NAME_SEPARATOR, scoreNames);

        return Mono.defer(() -> Flux.fromIterable(entityIds)
                .flatMap(entityId -> {
                    String member = member(workspaceId, scope, entityId);
                    return pending.addIfAbsent(dueAt, member)
                            .then(userName == null ? Mono.empty() : authors.fastPut(member, userName))
                            .then(scoreNames.isEmpty()
                                    ? Mono.empty()
                                    : pendingScoreNames.fastPut(member, encodedNames));
                })
                .then()
                .doOnSuccess(__ -> log.debug(
                        "Recorded '{}' entities for routing, scope '{}', workspace '{}', due at '{}'",
                        entityIds.size(), scope, workspaceId, dueAt))
                .doOnError(error -> log.error("Failed to record entities for routing, workspace '{}'",
                        workspaceId, error)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Publishes the entities whose debounce window has elapsed and returns how many were published.
     *
     * <p>Grouped by {@code (workspaceId, scope, author)} because a message carries one of each, and the
     * author is stamped on the queue item as {@code created_by}. Grouping barely fragments in practice —
     * a burst usually comes from one API key — and the payoff is large: the entities in one message have
     * their scores read in a single query instead of one query each.
     *
     * <p>Published first, removed only once the stream has the message. The other order is what reads as
     * safe - it cannot hand the same entity to two replicas - but it trades a harmless outcome for a
     * permanent one: a duplicate message costs nothing, because the consumer re-derives everything from
     * current state and {@code addItems} excludes what a queue has already held, whereas a failed publish
     * after the members are gone drops those entities for good, and there is no backfill to recover them.
     * Leaving them in the buffer means the next tick simply retries them - their deadline is already past.
     */
    public Mono<Long> flush() {
        if (!config.isEnabled()) {
            return Mono.just(0L);
        }

        return readDue(config.getJobBatchSize())
                .flatMap(due -> {
                    if (due.isEmpty()) {
                        return Mono.just(0L);
                    }

                    List<MessageGroup> groups = group(due);

                    return Flux.fromIterable(groups)
                            .concatMap(this::publish)
                            .reduce(0L, Long::sum)
                            .doOnNext(published -> log.info(
                                    "Flushed '{}' entities for routing in '{}' messages", published,
                                    groups.size()));
                });
    }

    private Mono<Long> publish(MessageGroup group) {
        return publisher.enqueue(group.workspaceId(), group.userName(), group.scope(), group.entityIds(),
                group.scoreNamesByEntity())
                .then(Mono.defer(() -> removePending(group)))
                .thenReturn((long) group.entityIds().size())
                // One group failing must not strand the others, and it no longer costs anything to
                // swallow: this group's members are still pending, so the next tick picks them up.
                .onErrorResume(error -> {
                    log.error("Failed to publish routing work for workspace '{}', '{}' entities; "
                            + "leaving them pending for the next flush", group.workspaceId(),
                            group.entityIds().size(), error);
                    return Mono.just(0L);
                });
    }

    private Mono<Void> removePending(MessageGroup group) {
        var pending = redisClient.getScoredSortedSet(PENDING_SET_KEY);
        var authors = redisClient.<String, String>getMap(PENDING_AUTHORS_KEY);

        List<String> members = group.entityIds().stream()
                .map(entityId -> member(group.workspaceId(), group.scope(), entityId))
                .toList();

        return pending.removeAll(members)
                .then(authors.fastRemove(members.toArray(String[]::new)))
                .then(redisClient.<String, String>getMap(PENDING_SCORE_NAMES_KEY)
                        .fastRemove(members.toArray(String[]::new)))
                .then();
    }

    /**
     * Reads the entities whose window has elapsed, leaving them in the buffer. {@link #removePending} takes
     * them out once their message is on the stream.
     *
     * <p>Reading without removing means a flush that overran its lock could hand the same entity to a
     * second replica. That is the intended trade — see {@link #flush()} — and the window is small: the lock
     * is released on completion rather than held to its TTL, so the next tick only overlaps a flush that
     * has already outlived {@code jobLockTime}.
     */
    private Mono<List<DueEntity>> readDue(int limit) {
        var pending = redisClient.getScoredSortedSet(PENDING_SET_KEY);
        var authors = redisClient.<String, String>getMap(PENDING_AUTHORS_KEY);
        var pendingScoreNames = redisClient.<String, String>getMap(PENDING_SCORE_NAMES_KEY);
        long now = Instant.now().toEpochMilli();

        return pending.valueRange(0, true, now, true, 0, limit)
                .map(due -> due.stream().map(String::valueOf).toList())
                .flatMap(members -> {
                    if (members.isEmpty()) {
                        return Mono.just(List.<DueEntity>of());
                    }
                    Set<String> memberKeys = Set.copyOf(members);
                    return Mono.zip(
                            authors.getAll(memberKeys).defaultIfEmpty(Map.of()),
                            pendingScoreNames.getAll(memberKeys).defaultIfEmpty(Map.of()))
                            .map(both -> members.stream()
                                    .map(member -> parse(member, both.getT1().get(member),
                                            decodeNames(both.getT2().get(member))))
                                    .filter(Objects::nonNull)
                                    .toList());
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    private Set<String> decodeNames(String encoded) {
        if (encoded == null || encoded.isEmpty()) {
            return Set.of();
        }
        return Set.of(encoded.split(NAME_SEPARATOR));
    }

    /**
     * One message per {@code (workspaceId, scope, author)}. The score names stay keyed by entity rather
     * than joining the group key, which would fragment a burst into a message per distinct score set and
     * undo most of what the batching is for.
     */
    private List<MessageGroup> group(List<DueEntity> due) {
        return due.stream()
                .collect(Collectors.groupingBy(
                        entity -> new GroupKey(entity.workspaceId(), entity.userName(), entity.scope()),
                        Collectors.toList()))
                .entrySet()
                .stream()
                .map(entry -> new MessageGroup(
                        entry.getKey().workspaceId(),
                        entry.getKey().userName(),
                        entry.getKey().scope(),
                        entry.getValue().stream().map(DueEntity::entityId).collect(Collectors.toSet()),
                        entry.getValue().stream()
                                .filter(entity -> !entity.scoreNames().isEmpty())
                                .collect(Collectors.toMap(DueEntity::entityId, DueEntity::scoreNames,
                                        (first, second) -> first))))
                .toList();
    }

    private record GroupKey(String workspaceId, String userName, AnnotationQueue.AnnotationScope scope) {
    }

    private String member(String workspaceId, AnnotationQueue.AnnotationScope scope, UUID entityId) {
        return workspaceId + MEMBER_SEPARATOR + scope.getValue() + MEMBER_SEPARATOR + entityId;
    }

    private DueEntity parse(String member, String userName, Set<String> scoreNames) {
        String[] parts = member.split(MEMBER_SEPARATOR);
        if (parts.length != 3) {
            log.warn("Discarding malformed pending routing member '{}'", member);
            return null;
        }

        try {
            return DueEntity.builder()
                    .workspaceId(parts[0])
                    .scope(AnnotationQueue.AnnotationScope.fromString(parts[1]))
                    .entityId(UUID.fromString(parts[2]))
                    .userName(userName)
                    .scoreNames(scoreNames)
                    .build();
        } catch (IllegalArgumentException exception) {
            log.warn("Discarding unparseable pending routing member '{}'", member, exception);
            return null;
        }
    }
}

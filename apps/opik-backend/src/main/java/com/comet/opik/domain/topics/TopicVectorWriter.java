package com.comet.opik.domain.topics;

import com.comet.opik.domain.topics.TopicVectorsDAO.Candidate;
import com.comet.opik.infrastructure.auth.RequestContext;
import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.Builder;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** Embeds one read's candidates with {@link TopicEmbedder} and writes each chunk's vectors as it arrives. */
@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class TopicVectorWriter {

    /** The user the deletes of {@link #dropDeleted} log as. */
    private static final String DELETE_USER = "topics-vectors";

    private final @NonNull TopicVectorsDAO dao;
    private final @NonNull TopicEmbedder embedder;
    private final @NonNull TopicVectorsMetrics metrics;
    private final @NonNull TopicsArrivalService arrival;

    /**
     * Embeds the candidates and writes each chunk, then gives the live vectors of all its chunks their topic on
     * arrival: the saved lists of their projects are read, and the vectors labelled and inserted, back to back. A read
     * that ends early (a cut budget, a failed chunk) still labels the vectors written before.
     *
     * @param missedBefore with the long-window pass, the short window's start: the vectors written for statuses before it
     *        are rows the fresh reads missed, and are counted as such
     * @param steps the workspace's topic on arrival so far this tick; the result carries it on
     */
    Mono<ChunkWrite> embedAndWrite(@NonNull TopicVectorsMetrics.Workspace workspace,
            @NonNull List<Candidate> candidates, @NonNull TickBudget budget, @Nullable Instant missedBefore,
            @NonNull ArrivalSteps steps) {
        var missed = missedBefore == null
                ? Set.<VectorKey>of()
                : candidates.stream().filter(candidate -> candidate.statusUpdatedAt().isBefore(missedBefore))
                        .map(VectorKey::of)
                        .collect(Collectors.toUnmodifiableSet());
        return embedder.embed(workspace, candidates, budget)
                .concatMap(outcome -> write(workspace, outcome, missed))
                // The error ends the fold as a value, so the chunks written before it still get labelled.
                .onErrorResume(error -> Mono.just(Written.failed(error)))
                .reduce(Written.EMPTY, Written::plus)
                .flatMap(written -> {
                    var projectIds = written.live().stream().map(TopicVector::projectId)
                            .collect(Collectors.toUnmodifiableSet());
                    return arrival.loadLabellers(workspace, projectIds, budget, steps)
                            .flatMap(read -> arrival.label(workspace, written.live(), budget, read))
                            .flatMap(next -> written.error() == null
                                    ? Mono.just(new ChunkWrite(written.result(), written.settled(), next))
                                    : Mono.error(written.error()));
                });
    }

    /**
     * Writes the chunk's vectors and the status rows of its traces no later tick would embed. A key is settled once its
     * row is in and {@link #dropDeleted} has checked it; a failed insert or check leaves its keys for a later tick. Only
     * the vectors of traces the check saw live are kept for arrival.
     */
    private Mono<Written> write(TopicVectorsMetrics.Workspace workspace, ChunkOutcome outcome,
            Set<VectorKey> missed) {
        return Mono.defer(() -> {
            outcome.skipped().forEach((reason, traces) -> metrics.skipped(workspace, reason, traces));
            var skipped = new TickResult(0, outcome.skipped());
            var vectors = outcome.vectors();
            return insertStatuses(workspace, outcome)
                    .flatMap(settledStatuses -> insertVectors(workspace, vectors).flatMap(vectorsIn -> {
                        var settled = new HashSet<>(settledStatuses);
                        if (vectorsIn) {
                            vectors.forEach(vector -> settled.add(VectorKey.of(vector)));
                        }
                        return dropDeleted(workspace, settled)
                                .flatMap(live -> {
                                    if (!vectorsIn) {
                                        // Not in: embedded again by a later tick.
                                        metrics.skipped(workspace, SkipReason.INSERT_FAILED, vectors.size());
                                        return Mono.just(Written.builder()
                                                .result(skipped.plus(TickResult.skipped(SkipReason.INSERT_FAILED,
                                                        vectors.size())))
                                                .settled(settled).live(List.of()).build());
                                    }
                                    metrics.written(workspace, vectors.size());
                                    metrics.longWindowMissed(workspace, (int) vectors.stream()
                                            .filter(vector -> missed.contains(VectorKey.of(vector))).count());
                                    var result = skipped.plus(TickResult.written(vectors.size()));
                                    var liveVectors = vectors.stream()
                                            .filter(vector -> live.contains(VectorKey.of(vector))).toList();
                                    return Mono.just(Written.builder().result(result).settled(settled)
                                            .live(liveVectors).build());
                                })
                                // In and then deleted unchecked: embedded again by a later tick.
                                .switchIfEmpty(Mono.fromSupplier(() -> {
                                    metrics.skipped(workspace, SkipReason.INSERT_FAILED, vectors.size());
                                    return Written.builder()
                                            .result(skipped.plus(TickResult.skipped(SkipReason.INSERT_FAILED,
                                                    vectors.size())))
                                            .settled(Set.of()).live(List.of()).build();
                                }));
                    }));
        });
    }

    /** Whether the vectors are in; none to write counts as in. */
    private Mono<Boolean> insertVectors(TopicVectorsMetrics.Workspace workspace, List<TopicVector> vectors) {
        if (vectors.isEmpty()) {
            return Mono.just(true);
        }
        return dao.insert(vectors)
                .thenReturn(true)
                .onErrorResume(exception -> {
                    log.warn("Topic vectors insert failed, embedding them again next tick, workspace '{}', rows '{}'",
                            workspace.id(), vectors.size(), exception);
                    return Mono.just(false);
                });
    }

    /**
     * Deletes the rows just written for traces deleted since the read, which no later read or cascade would find. The
     * trace-delete cascade deletes {@code ai_label_status} before {@code topic_vectors}: a cascade before this check
     * shows here as a trace without a status, and one after it finds the rows. Emits the written keys of live traces.
     * Empty when the check fails: the rows are then deleted and their keys left unsettled, so a later tick embeds a live
     * trace again and never reads a deleted one. Only when that delete fails too can a deleted trace's rows stay, until
     * retention.
     */
    private Mono<Set<VectorKey>> dropDeleted(TopicVectorsMetrics.Workspace workspace, Set<VectorKey> written) {
        if (written.isEmpty()) {
            return Mono.just(Set.of());
        }
        return dao.findLiveTraceIds(workspace.id(), TopicEmbedding.SUMMARY_TASK, written)
                .flatMap(live -> {
                    var kept = written.stream().filter(key -> live.contains(key.traceId()))
                            .collect(Collectors.toUnmodifiableSet());
                    var gone = written.stream().filter(key -> !kept.contains(key)).toList();
                    if (gone.isEmpty()) {
                        return Mono.just(kept);
                    }
                    log.info("Deleting the topic vectors of traces deleted during their embed, workspace '{}', "
                            + "traces '{}'", workspace.id(), gone.size());
                    return delete(workspace, gone).thenReturn(kept);
                })
                .onErrorResume(exception -> {
                    log.warn("Topic vectors deleted-trace check failed, deleting the chunk's rows to embed them again "
                            + "next tick, workspace '{}', rows '{}'", workspace.id(), written.size(), exception);
                    return delete(workspace, written)
                            .onErrorResume(deleteException -> {
                                log.error("Failed to delete an unchecked chunk's topic vectors, workspace '{}', rows "
                                        + "'{}'", workspace.id(), written.size(), deleteException);
                                return Mono.empty();
                            })
                            .then(Mono.<Set<VectorKey>>empty());
                });
    }

    /** Deletes the keys' traces from both tables, one cascade call per project. */
    private Mono<Void> delete(TopicVectorsMetrics.Workspace workspace, Collection<VectorKey> keys) {
        return Flux.fromIterable(keys.stream().collect(Collectors.groupingBy(VectorKey::projectId,
                Collectors.mapping(VectorKey::traceId, Collectors.toUnmodifiableSet()))).entrySet())
                .concatMap(project -> dao.deleteByTraceIds(project.getValue(), project.getKey()))
                .then()
                .contextWrite(context -> context.put(RequestContext.WORKSPACE_ID, workspace.id())
                        .put(RequestContext.USER_NAME, DELETE_USER));
    }

    /** The keys whose status rows are in: all of the chunk's, or none when the insert failed. */
    private Mono<Set<VectorKey>> insertStatuses(TopicVectorsMetrics.Workspace workspace, ChunkOutcome outcome) {
        if (outcome.statuses().isEmpty()) {
            return Mono.just(Set.of());
        }
        return dao.insertStatuses(outcome.statuses())
                .thenReturn(outcome.settled())
                .onErrorResume(exception -> {
                    log.warn("Topic vector status insert failed, embedding them again next tick, workspace '{}', rows "
                            + "'{}'", workspace.id(), outcome.statuses().size(), exception);
                    return Mono.just(Set.of());
                });
    }

    /**
     * What writing one read's chunks produced: its counts, the keys of its traces the watermark may pass, and the
     * workspace's topic on arrival after them.
     */
    record ChunkWrite(@NonNull TickResult result, @NonNull Set<VectorKey> settled, @NonNull ArrivalSteps arrival) {

        /**
         * Whether some of {@code candidates} were left for a later tick: neither written nor given a status row, for
         * want of time, a back-off, an insert, or an answer of the wrong dimensions.
         */
        boolean leftForLater(@NonNull List<Candidate> candidates) {
            return candidates.stream().map(VectorKey::of).anyMatch(key -> !settled.contains(key));
        }
    }

    /**
     * The chunks of one read written so far: their counts, settled keys and the vectors kept for arrival, and the error
     * that ended the read early, if one did.
     */
    @Builder(toBuilder = true)
    private record Written(@NonNull TickResult result, @NonNull Set<VectorKey> settled,
            @NonNull List<TopicVector> live, @Nullable Throwable error) {

        static final Written EMPTY = Written.builder().result(TickResult.EMPTY).settled(Set.of()).live(List.of())
                .build();

        static Written failed(@NonNull Throwable error) {
            return EMPTY.toBuilder().error(error).build();
        }

        Written plus(@NonNull Written other) {
            var union = new HashSet<>(settled);
            union.addAll(other.settled);
            var allLive = new ArrayList<>(live);
            allLive.addAll(other.live);
            return new Written(result.plus(other.result), union, allLive, error != null ? error : other.error);
        }
    }
}

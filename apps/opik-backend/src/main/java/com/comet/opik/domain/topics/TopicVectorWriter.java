package com.comet.opik.domain.topics;

import com.comet.opik.domain.topics.TopicVectorsDAO.Candidate;
import com.comet.opik.infrastructure.auth.RequestContext;
import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
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
     * Reads the saved lists of the candidates' projects for topic on arrival, then embeds the candidates and writes
     * each chunk, with its arrival rows.
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
        var projectIds = candidates.stream().map(Candidate::projectId).collect(Collectors.toUnmodifiableSet());
        return arrival.loadLabellers(workspace, projectIds, budget, steps).flatMap(read -> {
            // concatMap writes the chunks one after another, so only one thread at a time touches it.
            var latest = new AtomicReference<>(read);
            return embedder.embed(workspace, candidates, budget)
                    .concatMap(outcome -> write(workspace, outcome, missed, budget, latest.get())
                            .doOnNext(written -> latest.set(written.arrival())))
                    .reduce(ChunkWrite.empty(read), ChunkWrite::plus);
        });
    }

    /**
     * Writes the chunk's vectors and the status rows of its traces no later tick would embed. A key is settled once its
     * row is in and {@link #dropDeleted} has checked it; a failed insert or check leaves its keys for a later tick. Only
     * the vectors of traces the check saw live get arrival rows.
     */
    private Mono<ChunkWrite> write(TopicVectorsMetrics.Workspace workspace, ChunkOutcome outcome,
            Set<VectorKey> missed, TickBudget budget, ArrivalSteps steps) {
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
                                        return Mono.just(new ChunkWrite(skipped.plus(TickResult.skipped(
                                                SkipReason.INSERT_FAILED, vectors.size())), settled, steps));
                                    }
                                    metrics.written(workspace, vectors.size());
                                    metrics.longWindowMissed(workspace, (int) vectors.stream()
                                            .filter(vector -> missed.contains(VectorKey.of(vector))).count());
                                    var result = skipped.plus(TickResult.written(vectors.size()));
                                    var liveVectors = vectors.stream()
                                            .filter(vector -> live.contains(VectorKey.of(vector))).toList();
                                    return arrival.label(workspace, liveVectors, budget, steps)
                                            .map(next -> new ChunkWrite(result, settled, next));
                                })
                                // In and then deleted unchecked: embedded again by a later tick.
                                .switchIfEmpty(Mono.fromSupplier(() -> {
                                    metrics.skipped(workspace, SkipReason.INSERT_FAILED, vectors.size());
                                    return new ChunkWrite(skipped.plus(TickResult.skipped(SkipReason.INSERT_FAILED,
                                            vectors.size())), Set.of(), steps);
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

        static ChunkWrite empty(@NonNull ArrivalSteps arrival) {
            return new ChunkWrite(TickResult.EMPTY, Set.of(), arrival);
        }

        /**
         * Whether some of {@code candidates} were left for a later tick: neither written nor given a status row, for
         * want of time, a back-off, an insert, or an answer of the wrong dimensions.
         */
        boolean leftForLater(@NonNull List<Candidate> candidates) {
            return candidates.stream().map(VectorKey::of).anyMatch(key -> !settled.contains(key));
        }

        ChunkWrite plus(@NonNull ChunkWrite other) {
            var union = new HashSet<>(settled);
            union.addAll(other.settled);
            // The later write's arrival: each write starts from the one before.
            return new ChunkWrite(result.plus(other.result), union, other.arrival);
        }
    }
}

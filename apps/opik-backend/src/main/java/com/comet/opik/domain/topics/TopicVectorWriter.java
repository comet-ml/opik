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

    /**
     * @param missedBefore with the long-window pass, the short window's start: the vectors written for statuses before it
     *        are rows the fresh reads missed, and are counted as such
     */
    Mono<ChunkWrite> embedAndWrite(@NonNull TopicVectorsMetrics.Workspace workspace,
            @NonNull List<Candidate> candidates, @NonNull TickBudget budget, @Nullable Instant missedBefore) {
        var missed = missedBefore == null
                ? Set.<VectorKey>of()
                : candidates.stream().filter(candidate -> candidate.statusUpdatedAt().isBefore(missedBefore))
                        .map(VectorKey::of)
                        .collect(Collectors.toUnmodifiableSet());
        return embedder.embed(workspace, candidates, budget)
                .concatMap(outcome -> write(workspace, outcome, missed))
                .reduce(ChunkWrite.EMPTY, ChunkWrite::plus);
    }

    /**
     * Writes the chunk's vectors and the status rows of its traces no later tick would embed. A key is settled once its
     * row is in and {@link #dropDeleted} has checked it; a failed insert or check leaves its keys for a later tick.
     */
    private Mono<ChunkWrite> write(TopicVectorsMetrics.Workspace workspace, ChunkOutcome outcome,
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
                        return dropDeleted(workspace, settled).map(checked -> {
                            // Not in, or in and then deleted unchecked: embedded again by a later tick.
                            int lost = vectorsIn && checked ? 0 : vectors.size();
                            metrics.skipped(workspace, SkipReason.INSERT_FAILED, lost);
                            var result = skipped.plus(TickResult.skipped(SkipReason.INSERT_FAILED, lost));
                            if (!checked) {
                                return new ChunkWrite(result, Set.of());
                            }
                            if (vectorsIn) {
                                metrics.written(workspace, vectors.size());
                                metrics.longWindowMissed(workspace, (int) vectors.stream()
                                        .filter(vector -> missed.contains(VectorKey.of(vector))).count());
                                result = result.plus(TickResult.written(vectors.size()));
                            }
                            return new ChunkWrite(result, settled);
                        });
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
     * shows here as a trace without a status, and one after it finds the rows. False when the check fails: the rows are
     * then deleted and their keys left unsettled, so a later tick embeds a live trace again and never reads a deleted
     * one. Only when that delete fails too can a deleted trace's rows stay, until retention.
     */
    private Mono<Boolean> dropDeleted(TopicVectorsMetrics.Workspace workspace, Set<VectorKey> written) {
        if (written.isEmpty()) {
            return Mono.just(true);
        }
        return dao.findLiveTraceIds(workspace.id(), TopicEmbedding.SUMMARY_TASK, written)
                .flatMap(live -> {
                    var gone = written.stream().filter(key -> !live.contains(key.traceId())).toList();
                    if (gone.isEmpty()) {
                        return Mono.just(true);
                    }
                    log.info("Deleting the topic vectors of traces deleted during their embed, workspace '{}', "
                            + "traces '{}'", workspace.id(), gone.size());
                    return delete(workspace, gone).thenReturn(true);
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
                            .thenReturn(false);
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

    /** What writing one read's chunks produced: its counts, and the keys of its traces the watermark may pass. */
    record ChunkWrite(@NonNull TickResult result, @NonNull Set<VectorKey> settled) {

        static final ChunkWrite EMPTY = new ChunkWrite(TickResult.EMPTY, Set.of());

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
            return new ChunkWrite(result.plus(other.result), union);
        }
    }
}

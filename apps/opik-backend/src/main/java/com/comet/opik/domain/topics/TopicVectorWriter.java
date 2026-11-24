package com.comet.opik.domain.topics;

import com.comet.opik.domain.topics.TopicVectorsDAO.Candidate;
import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** Embeds one read's candidates with {@link TopicEmbedder} and writes each chunk's vectors as it arrives. */
@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class TopicVectorWriter {

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
     * row is in; a failed insert leaves its keys for a later tick.
     */
    private Mono<ChunkWrite> write(TopicVectorsMetrics.Workspace workspace, ChunkOutcome outcome,
            Set<VectorKey> missed) {
        return Mono.defer(() -> {
            outcome.skipped().forEach((reason, traces) -> metrics.skipped(workspace, reason, traces));
            var skipped = new TickResult(0, outcome.skipped());
            return insertStatuses(workspace, outcome).flatMap(settledStatuses -> {
                var vectors = outcome.vectors();
                if (vectors.isEmpty()) {
                    return Mono.just(new ChunkWrite(skipped, settledStatuses));
                }
                return dao.insert(vectors)
                        .then(Mono.fromCallable(() -> {
                            metrics.written(workspace, vectors.size());
                            metrics.longWindowMissed(workspace, (int) vectors.stream()
                                    .filter(vector -> missed.contains(VectorKey.of(vector))).count());
                            var settled = new HashSet<>(settledStatuses);
                            vectors.forEach(vector -> settled.add(VectorKey.of(vector)));
                            return new ChunkWrite(skipped.plus(TickResult.written(vectors.size())), settled);
                        }))
                        .onErrorResume(exception -> {
                            log.warn("Topic vectors insert failed, embedding them again next tick, workspace '{}', "
                                    + "rows '{}'", workspace.id(), vectors.size(), exception);
                            metrics.skipped(workspace, SkipReason.INSERT_FAILED, vectors.size());
                            return Mono.just(new ChunkWrite(
                                    skipped.plus(TickResult.skipped(SkipReason.INSERT_FAILED, vectors.size())),
                                    settledStatuses));
                        });
            });
        });
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

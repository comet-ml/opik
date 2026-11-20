package com.comet.opik.domain.topics;

import com.comet.opik.domain.topics.TopicVectorsDAO.Candidate;
import com.comet.opik.domain.topics.TopicVectorsTickService.VectorKey;
import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Embeds one read's candidates with {@link TopicEmbedder} and writes each chunk's vectors as it arrives, then gives the
 * chunk's traces their topic on arrival.
 */
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
     * @param arrival the tick's arrival steps
     */
    Mono<TickResult> embedAndWrite(@NonNull TopicVectorsMetrics.Workspace workspace,
            @NonNull List<Candidate> candidates, @NonNull TickBudget budget, @Nullable Instant missedBefore,
            @NonNull TopicsArrivalService.Tick arrival) {
        return embedAndWrite(workspace, candidates, budget, missedBefore, arrival, ConcurrentHashMap.newKeySet());
    }

    /** As above, adding the key of each vector written to {@code written}. */
    Mono<TickResult> embedAndWrite(@NonNull TopicVectorsMetrics.Workspace workspace,
            @NonNull List<Candidate> candidates, @NonNull TickBudget budget, @Nullable Instant missedBefore,
            @NonNull TopicsArrivalService.Tick arrival, @NonNull Set<VectorKey> written) {
        var missed = missedBefore == null
                ? Set.<VectorKey>of()
                : candidates.stream().filter(candidate -> candidate.statusUpdatedAt().isBefore(missedBefore))
                        .map(VectorKey::of)
                        .collect(Collectors.toUnmodifiableSet());
        return embedder.embed(workspace, candidates, budget)
                .concatMap(outcome -> write(workspace, outcome, missed, budget, arrival, written))
                .reduce(TickResult.EMPTY, TickResult::plus);
    }

    private Mono<TickResult> write(TopicVectorsMetrics.Workspace workspace, ChunkOutcome outcome, Set<VectorKey> missed,
            TickBudget budget, TopicsArrivalService.Tick arrival, Set<VectorKey> written) {
        return Mono.defer(() -> {
            outcome.skipped().forEach((reason, traces) -> metrics.skipped(workspace, reason, traces));
            var skipped = new TickResult(0, outcome.skipped());
            var vectors = outcome.vectors();
            if (vectors.isEmpty()) {
                return Mono.just(skipped);
            }
            return dao.insert(vectors)
                    .then(Mono.fromCallable(() -> {
                        vectors.forEach(vector -> written.add(VectorKey.of(vector)));
                        metrics.written(workspace, vectors.size());
                        metrics.longWindowMissed(workspace,
                                (int) vectors.stream().filter(vector -> missed.contains(VectorKey.of(vector))).count());
                        return skipped.plus(TickResult.written(vectors.size()));
                    }))
                    // Only after the insert; arrival never fails, so the handler below sees only insert errors.
                    .flatMap(result -> arrival.label(workspace.id(), vectors, budget.deadline()).thenReturn(result))
                    .onErrorResume(exception -> {
                        log.warn("Topic vectors insert failed, embedding them again next tick, workspace '{}', rows "
                                + "'{}'", workspace.id(), vectors.size(), exception);
                        metrics.skipped(workspace, SkipReason.INSERT_FAILED, vectors.size());
                        return Mono.just(skipped.plus(TickResult.skipped(SkipReason.INSERT_FAILED, vectors.size())));
                    });
        });
    }
}

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
import java.util.List;
import java.util.Set;
import java.util.UUID;
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
    Mono<TickResult> embedAndWrite(@NonNull String workspaceId, @NonNull List<Candidate> candidates,
            @NonNull TickBudget budget, @Nullable Instant missedBefore, @NonNull TopicsArrivalService.Tick arrival) {
        var missed = missedBefore == null
                ? Set.<Key>of()
                : candidates.stream().filter(candidate -> candidate.statusUpdatedAt().isBefore(missedBefore))
                        .map(candidate -> new Key(candidate.projectId(), candidate.traceId(), candidate.taskVersion()))
                        .collect(Collectors.toUnmodifiableSet());
        return embedder.embed(workspaceId, candidates, budget)
                .concatMap(outcome -> write(workspaceId, outcome, missed, budget, arrival))
                .reduce(TickResult.EMPTY, TickResult::plus);
    }

    private Mono<TickResult> write(String workspaceId, ChunkOutcome outcome, Set<Key> missed, TickBudget budget,
            TopicsArrivalService.Tick arrival) {
        return Mono.defer(() -> {
            outcome.skipped().forEach((reason, traces) -> metrics.skipped(workspaceId, reason, traces));
            var skipped = new TickResult(0, outcome.skipped());
            var vectors = outcome.vectors();
            if (vectors.isEmpty()) {
                return Mono.just(skipped);
            }
            return dao.insert(vectors)
                    .then(Mono.fromCallable(() -> {
                        metrics.written(workspaceId, vectors.size());
                        metrics.longWindowMissed(workspaceId, (int) vectors.stream()
                                .filter(vector -> missed.contains(
                                        new Key(vector.projectId(), vector.traceId(), vector.taskVersion())))
                                .count());
                        return skipped.plus(TickResult.written(vectors.size()));
                    }))
                    // Only after the insert; arrival never fails, so the handler below sees only insert errors.
                    .flatMap(result -> arrival.label(workspaceId, vectors, budget.deadline()).thenReturn(result))
                    .onErrorResume(exception -> {
                        log.warn("Topic vectors insert failed, workspace '{}', rows '{}'; embedding them again next "
                                + "tick", workspaceId, vectors.size(), exception);
                        metrics.skipped(workspaceId, SkipReason.INSERT_FAILED, vectors.size());
                        return Mono.just(skipped.plus(TickResult.skipped(SkipReason.INSERT_FAILED, vectors.size())));
                    });
        });
    }

    private record Key(UUID projectId, UUID traceId, int taskVersion) {
    }
}

package com.comet.opik.domain.topics;

import com.comet.opik.domain.topics.TopicVectorsDAO.Candidate;
import lombok.NonNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * One chunk's embed turn: the vectors to write, the chunk's traces that got none, by reason, and among those the ones no
 * later tick would embed either (an input refused on its own, a zero or non-finite vector), whose status rows are
 * written next to the vectors.
 */
record ChunkOutcome(@NonNull List<TopicVector> vectors, @NonNull Map<SkipReason, Integer> skipped,
        @NonNull List<TopicVectorStatus> statuses) {

    static final ChunkOutcome EMPTY = new ChunkOutcome(List.of(), Map.of(), List.of());

    /** Traces a later tick tries again. */
    static ChunkOutcome skipped(@NonNull SkipReason reason, int traces) {
        return new ChunkOutcome(List.of(), Map.of(reason, traces), List.of());
    }

    /** One input the service refused on its own: a later tick would send it and get the same answer. */
    static ChunkOutcome refused(@NonNull Candidate candidate) {
        return new ChunkOutcome(List.of(), Map.of(SkipReason.EMBED_REFUSED, 1),
                List.of(new TopicVectorStatus(candidate, SkipReason.EMBED_REFUSED)));
    }

    /** The keys no later tick would embed. */
    Set<VectorKey> settled() {
        return statuses.stream().map(status -> VectorKey.of(status.candidate()))
                .collect(Collectors.toUnmodifiableSet());
    }

    ChunkOutcome plus(@NonNull ChunkOutcome other) {
        var vectors = new ArrayList<>(this.vectors);
        vectors.addAll(other.vectors);
        var skipped = new HashMap<>(this.skipped);
        other.skipped.forEach((reason, traces) -> skipped.merge(reason, traces, Integer::sum));
        var statuses = new ArrayList<>(this.statuses);
        statuses.addAll(other.statuses);
        return new ChunkOutcome(vectors, skipped, statuses);
    }
}

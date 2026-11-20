package com.comet.opik.domain.topics;

import com.comet.opik.domain.topics.TopicVectorsDAO.Candidate;
import com.comet.opik.domain.topics.TopicVectorsTickService.VectorKey;
import lombok.NonNull;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * One chunk's embed turn: the vectors to write, the chunk's traces that got none, by reason, and among those the keys of
 * the ones no later tick would embed either (a refused input, a zero or non-finite vector), which the watermark may pass.
 */
record ChunkOutcome(@NonNull List<TopicVector> vectors, @NonNull Map<SkipReason, Integer> skipped,
        @NonNull Set<VectorKey> settled) {

    /** Traces a later tick tries again. */
    static ChunkOutcome skipped(@NonNull SkipReason reason, int traces) {
        return new ChunkOutcome(List.of(), Map.of(reason, traces), Set.of());
    }

    /** A chunk whose inputs the service refused: a later tick would send the same inputs and get the same answer. */
    static ChunkOutcome refused(@NonNull List<Candidate> chunk) {
        return new ChunkOutcome(List.of(), Map.of(SkipReason.EMBED_FAILED, chunk.size()), keys(chunk));
    }

    static Set<VectorKey> keys(@NonNull List<Candidate> candidates) {
        return candidates.stream().map(VectorKey::of).collect(Collectors.toUnmodifiableSet());
    }
}

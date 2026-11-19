package com.comet.opik.domain.topics;

import lombok.NonNull;

import java.util.List;
import java.util.Map;

/** One chunk's embed turn: the vectors to write, and the chunk's traces that got none, by reason. */
record ChunkOutcome(@NonNull List<TopicVector> vectors, @NonNull Map<SkipReason, Integer> skipped) {

    static ChunkOutcome skipped(@NonNull SkipReason reason, int traces) {
        return new ChunkOutcome(List.of(), Map.of(reason, traces));
    }
}

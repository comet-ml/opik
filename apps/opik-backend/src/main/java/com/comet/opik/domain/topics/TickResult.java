package com.comet.opik.domain.topics;

import lombok.Builder;
import lombok.NonNull;

import java.util.Map;

/** What one topic vectors tick wrote, and the traces it found but skipped, by reason. */
@Builder(toBuilder = true)
public record TickResult(int written, @NonNull Map<SkipReason, Integer> skipped) {

    static final TickResult EMPTY = new TickResult(0, Map.of());

    public TickResult {
        skipped = Map.copyOf(skipped);
    }
}

package com.comet.opik.domain.topics;

import java.util.EnumMap;
import java.util.Map;

/** One tick's running counts. Used by the tick's own thread only. */
final class TickCounts {

    private final Map<SkipReason, Integer> skipped = new EnumMap<>(SkipReason.class);
    private int written;

    void skipped(SkipReason reason, int traces) {
        if (traces > 0) {
            skipped.merge(reason, traces, Integer::sum);
        }
    }

    void written(int rows) {
        written += rows;
    }

    TickResult result() {
        return new TickResult(written, skipped);
    }
}

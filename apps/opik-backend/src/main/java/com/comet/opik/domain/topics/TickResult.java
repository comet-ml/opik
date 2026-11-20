package com.comet.opik.domain.topics;

import lombok.Builder;
import lombok.NonNull;

import java.util.EnumMap;
import java.util.Map;

/**
 * What one topic vectors tick wrote, and the traces it found but skipped, by reason; {@code longWindowPass} when the
 * tick ran the daily long-window pass in place of its fresh read.
 */
@Builder(toBuilder = true)
public record TickResult(int written, @NonNull Map<SkipReason, Integer> skipped, boolean longWindowPass) {

    static final TickResult EMPTY = new TickResult(0, Map.of());

    public TickResult {
        skipped = Map.copyOf(skipped);
    }

    public TickResult(int written, @NonNull Map<SkipReason, Integer> skipped) {
        this(written, skipped, false);
    }

    static TickResult written(int rows) {
        return new TickResult(rows, Map.of());
    }

    /**
     * Whether some of the traces found were left for a later tick for want of time, an embed call or an insert: the read
     * that found them did not finish its work. An invalid vector is not: the next tick would get the same answer.
     */
    boolean cut() {
        return skipped.containsKey(SkipReason.OVER_BUDGET) || skipped.containsKey(SkipReason.EMBED_FAILED)
                || skipped.containsKey(SkipReason.INSERT_FAILED);
    }

    static TickResult skipped(@NonNull SkipReason reason, int traces) {
        return traces > 0 ? new TickResult(0, Map.of(reason, traces)) : EMPTY;
    }

    TickResult plus(@NonNull TickResult other) {
        var sum = new EnumMap<SkipReason, Integer>(SkipReason.class);
        sum.putAll(skipped);
        other.skipped.forEach((reason, traces) -> sum.merge(reason, traces, Integer::sum));
        return new TickResult(written + other.written, sum, longWindowPass || other.longWindowPass);
    }
}

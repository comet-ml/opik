package com.comet.opik.domain.topics;

import lombok.Builder;
import lombok.NonNull;

import java.util.EnumMap;
import java.util.Map;

/** What one topic vectors tick wrote, and the traces it found but skipped, by reason. */
@Builder(toBuilder = true)
public record TickResult(int written, @NonNull Map<SkipReason, Integer> skipped) {

    static final TickResult EMPTY = new TickResult(0, Map.of());

    public TickResult {
        skipped = Map.copyOf(skipped);
    }

    static TickResult written(int rows) {
        return new TickResult(rows, Map.of());
    }

    static TickResult skipped(@NonNull SkipReason reason, int traces) {
        return traces > 0 ? new TickResult(0, Map.of(reason, traces)) : EMPTY;
    }

    TickResult plus(@NonNull TickResult other) {
        var sum = new EnumMap<SkipReason, Integer>(SkipReason.class);
        sum.putAll(skipped);
        other.skipped.forEach((reason, traces) -> sum.merge(reason, traces, Integer::sum));
        return new TickResult(written + other.written, sum);
    }
}

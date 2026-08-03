package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.Span;
import com.comet.opik.utils.JsonUtils;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Mutable accumulator behind {@link AgenticScoringService#preloadThreadSpansBounded}: buffers spans while
 * tracking their approximate serialized size and reports when the running total crosses the cap.
 *
 * <p>Not synchronized, and safe only because it is confined to a single reactive subscription and touched
 * exclusively from that pipeline's serial signals. The Reactive Streams contract delivers {@code onNext}
 * and the terminal signal sequentially with happens-before ordering between them, so the field mutations
 * are visible without locking. It must not be shared across subscriptions or threads.
 */
@RequiredArgsConstructor
final class BoundedSpanAccumulator {

    private final long maxPreloadBytes;
    private final List<Span> spans = new ArrayList<>();
    private long approxBytes;
    private boolean overflowed;

    /**
     * Buffers {@code span} unless the cap has been (or is now) crossed.
     *
     * @return {@code true} once the running size crosses the cap, signalling the caller to stop
     */
    boolean addAndCheckOverflow(@NonNull Span span) {
        if (overflowed) {
            return true;
        }
        approxBytes += approxSpanBytes(span);
        if (approxBytes > maxPreloadBytes) {
            overflowed = true;
            spans.clear(); // over the cap: drop the buffer; the tools path drills per-trace on demand
            return true;
        }
        spans.add(span);
        return false;
    }

    ThreadSpanPreload toPreload() {
        return ThreadSpanPreload.builder()
                .spans(overflowed ? List.of() : List.copyOf(spans))
                .approxBytes(approxBytes)
                .overflowed(overflowed)
                .build();
    }

    private long approxSpanBytes(Span span) {
        return JsonUtils.getSerializedLengthInBytes(span.input())
                + JsonUtils.getSerializedLengthInBytes(span.output())
                + JsonUtils.getSerializedLengthInBytes(span.metadata());
    }
}

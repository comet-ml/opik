package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.Span;
import lombok.Builder;
import lombok.NonNull;

import java.util.List;

/**
 * Result of {@link AgenticScoringService#preloadThreadSpansBounded}. {@code spans} holds the buffered
 * spans when the thread fits under the cap (inline/enriched path); it is empty when {@code overflowed},
 * since the agentic-tools path drills per-trace on demand and needs no buffer. {@code approxBytes} is the
 * accumulated approximate serialized size seen before stopping.
 */
@Builder(toBuilder = true)
public record ThreadSpanPreload(@NonNull List<Span> spans, long approxBytes, boolean overflowed) {
}

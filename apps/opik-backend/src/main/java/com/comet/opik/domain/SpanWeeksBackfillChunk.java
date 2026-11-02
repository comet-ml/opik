package com.comet.opik.domain;

import jakarta.annotation.Nullable;
import lombok.Builder;

/**
 * A planned range of the span weeks backfill: the span ids from Monday {@code fromWeek}'s UUIDv7 up to, not including,
 * Monday {@code toWeek}'s, both YYYYMMDD. {@code fromWeek} 0 leaves the range open below, and a null {@code toWeek}
 * leaves it open above.
 */
@Builder(toBuilder = true)
public record SpanWeeksBackfillChunk(long fromWeek, @Nullable Long toWeek) {
}

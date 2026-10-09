package com.comet.opik.domain;

import lombok.Builder;
import lombok.NonNull;

import java.util.UUID;

/**
 * One span_weeks row: a weekly partition of the spans table that holds spans of the given trace.
 */
@Builder(toBuilder = true)
public record SpanWeek(@NonNull UUID projectId, @NonNull UUID traceId, long idWeek) {
}

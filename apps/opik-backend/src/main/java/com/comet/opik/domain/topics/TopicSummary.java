package com.comet.opik.domain.topics;

import lombok.Builder;
import lombok.NonNull;

import java.util.UUID;

/** A trace's latest {@code ai_labels} output row: its task version and {@code summary}, '' when it has none. */
@Builder(toBuilder = true)
public record TopicSummary(@NonNull UUID traceId, int taskVersion, @NonNull String summary) {
}

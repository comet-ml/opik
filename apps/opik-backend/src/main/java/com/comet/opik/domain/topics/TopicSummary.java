package com.comet.opik.domain.topics;

import lombok.Builder;
import lombok.NonNull;

import java.util.UUID;

/** A trace's latest {@code ai_labels} output row, by project (a trace id reused in another project is another trace): its task version and {@code summary}, '' when it has none. */
@Builder(toBuilder = true)
public record TopicSummary(@NonNull UUID projectId, @NonNull UUID traceId, int taskVersion,
        @NonNull String summary) {
}

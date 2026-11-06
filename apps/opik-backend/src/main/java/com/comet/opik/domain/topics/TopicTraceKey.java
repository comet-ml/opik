package com.comet.opik.domain.topics;

import lombok.Builder;
import lombok.NonNull;

import java.util.UUID;

/** A trace by its project: a trace id reused in another project is another trace. */
@Builder(toBuilder = true)
public record TopicTraceKey(@NonNull UUID projectId, @NonNull UUID traceId) {
}

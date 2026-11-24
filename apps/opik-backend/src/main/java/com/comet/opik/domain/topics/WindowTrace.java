package com.comet.opik.domain.topics;

import java.time.Instant;
import java.util.UUID;

/** A window trace: its id and creation time, the keys of its {@code trace_topics} row. */
record WindowTrace(UUID traceId, Instant traceCreatedAt) {
}

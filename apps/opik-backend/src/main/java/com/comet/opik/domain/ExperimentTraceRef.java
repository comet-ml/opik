package com.comet.opik.domain;

import java.util.UUID;

public record ExperimentTraceRef(UUID experimentId, UUID traceId) {
}

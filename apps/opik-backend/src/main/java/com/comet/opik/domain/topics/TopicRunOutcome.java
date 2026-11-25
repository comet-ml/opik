package com.comet.opik.domain.topics;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Builder;
import lombok.NonNull;

import java.util.UUID;

/** How a run ended, with the stats it logged. {@code errorCode} is null for a saved run. */
@Builder(toBuilder = true)
record TopicRunOutcome(@NonNull UUID runId, @NonNull TopicProject.RunStatus status, String errorCode,
        @NonNull JsonNode stats) {
}

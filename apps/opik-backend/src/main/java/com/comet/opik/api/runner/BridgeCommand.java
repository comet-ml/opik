package com.comet.opik.api.runner;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Builder;

import java.time.Instant;
import java.util.UUID;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record BridgeCommand(
        UUID commandId,
        UUID runnerId,
        BridgeCommandType type,
        BridgeCommandStatus status,
        JsonNode args,
        JsonNode result,
        JsonNode error,
        int timeoutSeconds,
        Instant submittedAt,
        Instant pickedUpAt,
        Instant completedAt,
        Long durationMs) {
}

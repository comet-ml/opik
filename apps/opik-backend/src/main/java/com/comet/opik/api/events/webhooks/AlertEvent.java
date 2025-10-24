package com.comet.opik.api.events.webhooks;

import com.comet.opik.api.AlertEventType;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;

import java.util.UUID;

/**
 * Represents an alert event to be evaluated before sending to external endpoints.
 */
@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record AlertEvent(@NotNull AlertEventType eventType,
        @NotBlank String workspaceId,
        @NotBlank String workspaceName,
        @NotBlank String userName,
        UUID projectId,
        @NotNull Object payload) {
}
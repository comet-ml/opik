package com.comet.opik.api.events.webhooks;

import com.comet.opik.api.AlertEventType;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.Getter;
import lombok.extern.jackson.Jacksonized;

import java.util.UUID;

/**
 * Represents an alert event to be evaluated before sending to external endpoints.
 */
@Builder(toBuilder = true)
@Jacksonized
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@Getter
public class AlertEvent {
    @NotNull private AlertEventType eventType;
    @NotBlank private String workspaceId;
    private UUID projectId;
    @NotNull private Object payload;
}
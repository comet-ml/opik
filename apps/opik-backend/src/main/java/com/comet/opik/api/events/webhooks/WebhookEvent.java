package com.comet.opik.api.events.webhooks;

import com.comet.opik.api.AlertEventType;
import com.comet.opik.api.AlertType;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.Getter;
import lombok.extern.jackson.Jacksonized;
import org.hibernate.validator.constraints.URL;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Represents a webhook event to be sent to external endpoints.
 */
@Builder(toBuilder = true)
@Jacksonized
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@Getter
public class WebhookEvent<T> {

    @NotBlank private String id;

    @NotNull private AlertEventType eventType;

    @NotNull private AlertType alertType;

    @NotNull private UUID alertId;

    @NotNull private String alertName;

    @NotNull Map<String, String> alertMetadata;

    @NotBlank private String workspaceId;

    @NotBlank private String workspaceName;

    @NotBlank private String userName;

    @NotNull private T payload;

    private String jsonPayload;

    @NotNull private Instant createdAt;

    @Builder.Default
    @Min(1) @Max(10) private int maxRetries = 3;

    @NotBlank @URL
    private String url;

    private String secret;

    private Map<String, String> headers;
}
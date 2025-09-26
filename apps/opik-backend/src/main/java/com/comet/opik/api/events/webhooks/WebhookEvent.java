package com.comet.opik.api.events.webhooks;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Builder;
import lombok.Getter;
import lombok.extern.jackson.Jacksonized;

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

    @JsonProperty("id")
    private String id;

    @JsonProperty("event_type")
    private WebhookEventTypes eventType;

    @JsonProperty("alert_id")
    private UUID alertId;

    @JsonProperty("workspace_id")
    private String workspaceId;

    @JsonProperty("payload")
    private T payload;

    @JsonProperty("created_at")
    private Instant createdAt;

    @JsonProperty("max_retries")
    @Builder.Default
    private int maxRetries = 3;

    @JsonProperty("url")
    private String url;

    @JsonProperty("secret")
    private String secret;

    @JsonProperty("headers")
    private Map<String, String> headers;
}
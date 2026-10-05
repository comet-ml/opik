package com.comet.opik.infrastructure;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * Mechanics only. Whether triggered alerts are published at all is serviceToggles.eventBridgeAlertsEnabled.
 */
@Data
public class AlertsEventBridgeConfig {

    // Bus name or ARN
    @JsonProperty
    private String eventBus;

    // Optional: falls back to the region in an ARN bus, then to the default AWS region provider chain
    @JsonProperty
    private String region;
}

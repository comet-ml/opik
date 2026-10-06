package com.comet.opik.infrastructure;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.AssertTrue;
import lombok.Data;
import org.apache.commons.lang3.StringUtils;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Mechanics only. Whether triggered alerts are published at all is serviceToggles.eventBridgeAlertsEnabled.
 */
@Data
public class AlertsEventBridgeConfig {

    private static final String REGION = "[a-z]{2}(?:-[a-z]+)+-\\d";
    private static final String BUS_NAME = "[/.\\-_A-Za-z0-9]{1,256}";
    private static final Pattern REGION_PATTERN = Pattern.compile(REGION);
    private static final Pattern BUS_NAME_PATTERN = Pattern.compile(BUS_NAME);
    private static final Pattern BUS_ARN_PATTERN = Pattern.compile(
            "arn:[a-z0-9-]+:events:(?<region>" + REGION + "):\\d{12}:event-bus/" + BUS_NAME);

    // Bus name or ARN
    @JsonProperty
    private String eventBus;

    // Optional: falls back to the region in an ARN bus, then to the default AWS region provider chain
    @JsonProperty
    private String region;

    @JsonIgnore
    @AssertTrue(message = "eventBus (ALERTS_EVENTBRIDGE_EVENT_BUS) must be an EventBridge bus name matching [/.-_A-Za-z0-9] of at most 256 characters, or a bus ARN arn:<partition>:events:<region>:<account>:event-bus/<name>")
    public boolean isEventBusValid() {
        return StringUtils.isBlank(eventBus)
                || BUS_NAME_PATTERN.matcher(eventBus).matches()
                || BUS_ARN_PATTERN.matcher(eventBus).matches();
    }

    @JsonIgnore
    @AssertTrue(message = "region (ALERTS_EVENTBRIDGE_REGION) must be an AWS region such as us-east-1")
    public boolean isRegionValid() {
        return StringUtils.isBlank(region) || REGION_PATTERN.matcher(region).matches();
    }

    @JsonIgnore
    public Optional<String> getEventBusArnRegion() {
        return Optional.ofNullable(eventBus)
                .map(BUS_ARN_PATTERN::matcher)
                .filter(Matcher::matches)
                .map(matcher -> matcher.group("region"));
    }
}

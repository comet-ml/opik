package com.comet.opik.api.runner;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Builder;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record BridgeCommandNextRequest(
        Integer maxCommands) {

    private static final int DEFAULT_MAX_COMMANDS = 10;
    private static final int MAX_MAX_COMMANDS = 20;

    public int effectiveMaxCommands() {
        if (maxCommands == null || maxCommands <= 0) {
            return DEFAULT_MAX_COMMANDS;
        }
        return Math.min(maxCommands, MAX_MAX_COMMANDS);
    }
}

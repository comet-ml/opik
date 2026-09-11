package com.comet.opik.api;

import com.comet.opik.infrastructure.db.HasValue;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import jakarta.ws.rs.BadRequestException;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;

@Getter
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public enum AgentInsightsIssueSeverity implements HasValue {
    CRITICAL("critical"),
    HIGH("high"),
    MEDIUM("medium"),
    LOW("low");

    @JsonValue
    private final String value;

    @JsonCreator
    public static AgentInsightsIssueSeverity fromString(String value) {
        return Arrays.stream(values())
                .filter(severity -> severity.value.equals(value))
                .findFirst()
                .orElseThrow(() -> new BadRequestException("Unknown issue severity: '%s'".formatted(value)));
    }
}

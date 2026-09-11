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
public enum AgentInsightsIssueStatus implements HasValue {
    OPEN("open"),
    RESOLVED("resolved"),
    CLOSED("closed");

    @JsonValue
    private final String value;

    // BadRequestException so an invalid query param value yields 400 instead of JAX-RS's default 404
    @JsonCreator
    public static AgentInsightsIssueStatus fromString(String value) {
        return Arrays.stream(values())
                .filter(status -> status.value.equals(value))
                .findFirst()
                .orElseThrow(() -> new BadRequestException("Unknown issue status: '%s'".formatted(value)));
    }
}

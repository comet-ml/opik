package com.comet.opik.api;

import com.comet.opik.infrastructure.db.HasValue;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import jakarta.ws.rs.BadRequestException;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;

/**
 * Discriminator for {@code report_failures.type} (a DB enum). Agent Insights is currently the only source;
 * the wire value {@code "agent_insights"} matches the DB enum literal.
 */
@Getter
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public enum ReportFailureType implements HasValue {
    AGENT_INSIGHTS("agent_insights");

    @JsonValue
    private final String value;

    // BadRequestException so an invalid body/query value yields 400 instead of JAX-RS's default 404.
    @JsonCreator
    public static ReportFailureType fromString(String value) {
        return Arrays.stream(values())
                .filter(type -> type.value.equals(value))
                .findFirst()
                .orElseThrow(() -> new BadRequestException("Unknown report failure type: '%s'".formatted(value)));
    }
}

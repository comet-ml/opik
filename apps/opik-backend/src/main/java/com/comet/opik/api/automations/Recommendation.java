package com.comet.opik.api.automations;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.annotation.Nullable;
import lombok.Builder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.UUID;

/**
 * A ranked next step for a project. Every recommendation that can be applied carries its own fix as a
 * ready write call, so an agent shows the reason, asks, and sends it; nothing here is advice to translate.
 */
@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record Recommendation(
        UUID id,
        Kind kind,
        @Nullable String workflow,
        @Nullable UUID automationId,
        String reason,
        JsonNode evidence,
        @Nullable String blockedUntil,
        @Nullable Apply apply) {

    /** The operation in the MCP's own vocabulary and the data to send with it. */
    @Builder(toBuilder = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Apply(String operation, JsonNode data) {
    }

    @Getter
    @RequiredArgsConstructor
    public enum Kind {
        SETUP("setup"),
        TUNE("tune"),
        REVIEW("review"),
        REMOVE("remove");

        @JsonValue
        private final String value;
    }
}

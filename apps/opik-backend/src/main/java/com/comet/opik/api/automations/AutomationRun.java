package com.comet.opik.api.automations;

import com.comet.opik.api.Page;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.annotation.Nullable;
import lombok.Builder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * One firing, or for score-triggered automations one hour of firings rolled up: the trigger, every step's
 * outcome, and the run whose write fired this one.
 */
@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record AutomationRun(
        UUID id,
        UUID automationId,
        UUID projectId,
        Kind kind,
        Status status,
        Trigger trigger,
        List<Step> steps,
        BigDecimal costUsd,
        @Nullable UUID parentRunId,
        List<UUID> childRunIds,
        @Nullable String lastError,
        Instant windowStart,
        Instant windowEnd,
        Instant startedAt,
        Instant finishedAt) {

    @Builder(toBuilder = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Trigger(String event, Automation.Scope scope, long fired, long matched, long sampled,
            @Nullable UUID itemId) {
    }

    /**
     * A rollup carries counters; a single (shadow) run carries one status with its output and reason.
     */
    @Builder(toBuilder = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Step(String id, String type, long done, long skipped, long failed, @Nullable StepStatus status,
            @Nullable JsonNode output, @Nullable String reason, @Nullable String lastError) {
    }

    @Getter
    @RequiredArgsConstructor
    public enum Kind {
        ROLLUP("rollup"),
        SINGLE("single");

        @JsonValue
        private final String value;
    }

    @Getter
    @RequiredArgsConstructor
    public enum Status {
        DONE("done"),
        PARTIAL("partial"),
        FAILED("failed");

        @JsonValue
        private final String value;

        @JsonCreator
        public static Status fromString(String value) {
            return Arrays.stream(values())
                    .filter(status -> status.value.equals(value))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown run status '%s'".formatted(value)));
        }
    }

    @Getter
    @RequiredArgsConstructor
    public enum StepStatus {
        DONE("done"),
        SKIPPED("skipped"),
        FAILED("failed");

        @JsonValue
        private final String value;
    }

    @Builder(toBuilder = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record AutomationRunPage(List<AutomationRun> content, int page, int size, long total)
            implements
                Page<AutomationRun> {
    }
}

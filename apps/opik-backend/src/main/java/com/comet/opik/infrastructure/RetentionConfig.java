package com.comet.opik.infrastructure;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

import java.time.Duration;
import java.time.LocalDate;

@Data
public class RetentionConfig {

    @Valid @JsonProperty
    private boolean enabled = false;

    /** How many times per day the retention job should run. Min 1 (once/day), max 288 (every 5 min). */
    @JsonProperty
    @Min(1) @Max(288) private int executionsPerDay = 48;

    /** Max number of workspace_ids per DELETE statement. */
    @JsonProperty
    @Min(1) @Max(10000) private int workspaceBatchSize = 1000;

    /** Size of the sliding window (days) processed per run. Covers missed executions. */
    @JsonProperty
    @Min(1) @Max(30) private int slidingWindowDays = 3;

    /** Catch-up configuration for progressive historical data deletion. */
    @Valid @JsonProperty
    private CatchUpConfig catchUp = new CatchUpConfig();

    /** Derived: interval between executions. */
    public Duration getInterval() {
        int intervalMinutes = (24 * 60) / executionsPerDay;
        return Duration.ofMinutes(intervalMinutes);
    }

    /** Derived: total number of fractions = executionsPerDay. */
    public int getTotalFractions() {
        return executionsPerDay;
    }

    @Data
    public static class CatchUpConfig {

        @JsonProperty
        private boolean enabled = true;

        /** Velocity threshold: below this → small workspace (one-shot delete). Spans/week. */
        @JsonProperty
        @Min(1) @Max(1_000_000) private long smallThreshold = 10_000;

        /** Velocity threshold: above this → large workspace (2-day chunks). Spans/week. */
        @JsonProperty
        @Min(1) @Max(10_000_000) private long largeThreshold = 100_000;

        /** Max small workspaces to process in a single catch-up cycle. */
        @JsonProperty
        @Min(1) @Max(1000) private int smallBatchSize = 200;

        /** Max medium workspaces to process in a single catch-up cycle. */
        @JsonProperty
        @Min(1) @Max(100) private int mediumBatchSize = 10;

        /** Chunk size in days for medium workspaces. */
        @JsonProperty
        @Min(1) @Max(30) private int mediumChunkDays = 7;

        /** Chunk size in days for large workspaces. */
        @JsonProperty
        @Min(1) @Max(7) private int largeChunkDays = 1;

        /** Default velocity assumed when the estimation query fails (TOO_MANY_ROWS). */
        @JsonProperty
        @Min(1) private long defaultVelocity = 1_000_000;

        /** Interval in minutes between catch-up cycles. */
        @JsonProperty
        @Min(1) @Max(1440) private int intervalMinutes = 45;

        /** Interval in minutes between estimation cycles for newly created rules. */
        @JsonProperty
        @Min(1) @Max(60) private int estimationIntervalMinutes = 5;

        /** Earliest possible date for catch-up cursor start (service launch date). */
        @JsonProperty
        private LocalDate serviceStartDate = LocalDate.of(2024, 9, 1);

        /** Derived: interval between catch-up executions. */
        public Duration getCatchUpInterval() {
            return Duration.ofMinutes(intervalMinutes);
        }
    }
}

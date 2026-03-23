package com.comet.opik.infrastructure;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

import java.time.Duration;

@Data
public class RetentionConfig {

    @Valid @JsonProperty
    private boolean enabled = false;

    /** How many times per day the retention job should run. Min 1 (once/day), max 288 (every 5 min). */
    @Valid @JsonProperty
    @Min(1) @Max(288) private int executionsPerDay = 48;

    /** Max number of workspace_ids per DELETE statement. */
    @Valid @JsonProperty
    @Min(1) @Max(10000) private int workspaceBatchSize = 1000;

    /** Size of the sliding window (days) processed per run. Covers missed executions. */
    @JsonProperty
    @Min(1) @Max(30) private int slidingWindowDays = 3;

    /** Max time (seconds) the job can hold the distributed lock. */
    @Valid @JsonProperty
    @Min(1) @Max(7200) private int lockTimeoutSeconds = 1800;

    /** Derived: interval between executions. */
    public Duration getInterval() {
        int intervalMinutes = (24 * 60) / executionsPerDay;
        return Duration.ofMinutes(intervalMinutes);
    }

    /** Derived: total number of fractions = executionsPerDay. */
    public int getTotalFractions() {
        return executionsPerDay;
    }
}

package com.comet.opik.infrastructure;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.dropwizard.util.Duration;
import jakarta.validation.Valid;
import lombok.Data;

@Data
public class OptimizationLogsConfig {

    @Valid @JsonProperty
    private boolean enabled = true;

    @Valid @JsonProperty
    private Duration syncInterval = Duration.minutes(2);

    @Valid @JsonProperty
    private Duration lockTimeout = Duration.seconds(30);

    @Valid @JsonProperty
    private Duration lockWaitTimeout = Duration.seconds(5);

    @Valid @JsonProperty
    private String s3PathPrefix = "optimization-logs";

    @Valid @JsonProperty
    private int maxOptimizationsPerSync = 100;

    /**
     * Get sync interval in seconds for use in scheduled job.
     */
    public long getSyncIntervalSeconds() {
        return syncInterval.toSeconds();
    }

    /**
     * Get lock timeout in seconds for use in distributed locking.
     */
    public long getLockTtlSeconds() {
        return lockTimeout.toSeconds();
    }
}

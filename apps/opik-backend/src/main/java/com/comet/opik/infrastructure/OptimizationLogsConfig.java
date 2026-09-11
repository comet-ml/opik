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
    private Duration syncInterval = Duration.minutes(1);

    @Valid @JsonProperty
    private Duration lockTimeout = Duration.seconds(30);

    @Valid @JsonProperty
    private int syncConcurrency = 5;

    /**
     * TTL for cancellation signal keys in Redis.
     * Should be longer than the maximum optimization execution timeout.
     */
    @Valid @JsonProperty
    private Duration cancellationKeyTtl = Duration.hours(1);

    public java.time.Duration getSyncInterval() {
        return syncInterval.toJavaDuration();
    }

    public java.time.Duration getLockTimeout() {
        return lockTimeout.toJavaDuration();
    }

    public long getCancellationKeyTtlSeconds() {
        return cancellationKeyTtl.toSeconds();
    }
}

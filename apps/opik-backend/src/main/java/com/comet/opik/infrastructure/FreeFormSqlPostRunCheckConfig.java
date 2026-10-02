package com.comet.opik.infrastructure;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.dropwizard.util.Duration;
import io.dropwizard.validation.MinDuration;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.concurrent.TimeUnit;

/** Timing of the free-form SQL post-run check's {@code system.query_log} reads and flushes. */
@Data
public class FreeFormSqlPostRunCheckConfig {

    /**
     * At most one flush per interval, per instance and across the cluster. Whole seconds: the cluster-wide permit is
     * a Redis rate limiter counted in seconds.
     */
    @JsonProperty
    private @NotNull @MinDuration(value = 1, unit = TimeUnit.SECONDS) Duration minFlushInterval = Duration.seconds(1);

    /** How soon a flush denied the cluster-wide permit asks for it again. */
    @JsonProperty
    private @NotNull @MinDuration(value = 1, unit = TimeUnit.MILLISECONDS) Duration flushPermitRetry = Duration
            .milliseconds(200);

    /** The wait between a log read that misses the query's entry and the next attempt. */
    @JsonProperty
    private @NotNull @MinDuration(value = 0, unit = TimeUnit.MILLISECONDS) Duration logRetryDelay = Duration
            .milliseconds(500);

    /** Flushes tried before the check fails closed and withholds the result. */
    @JsonProperty
    private @Min(1) @Max(10) int maxFlushAttempts = 3;
}

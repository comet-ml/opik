package com.comet.opik.infrastructure;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.dropwizard.util.Duration;
import io.dropwizard.validation.MinDuration;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.concurrent.TimeUnit;

@Data
public class RunnerConfig {

    @Valid @JsonProperty
    private boolean enabled;

    @Valid @NotNull @JsonProperty
    @MinDuration(value = 1, unit = TimeUnit.SECONDS)
    private Duration jobTimeout = Duration.seconds(1800);

    @Valid @JsonProperty
    private int maxPendingJobsPerRunner = 100;

    @Valid @NotNull @JsonProperty
    @MinDuration(value = 1, unit = TimeUnit.SECONDS)
    private Duration heartbeatTtl = Duration.seconds(15);

    @Valid @NotNull @JsonProperty
    @MinDuration(value = 1, unit = TimeUnit.SECONDS)
    private Duration nextJobPollTimeout = Duration.seconds(10);

    @Valid @NotNull @JsonProperty
    @MinDuration(value = 1, unit = TimeUnit.HOURS)
    private Duration deadRunnerPurgeTime = Duration.hours(24);

    @Valid @NotNull @JsonProperty
    @MinDuration(value = 1, unit = TimeUnit.SECONDS)
    private Duration completedJobTtl = Duration.days(7);

    @Valid @NotNull @JsonProperty
    @MinDuration(value = 1, unit = TimeUnit.SECONDS)
    private Duration reaperLockDuration = Duration.seconds(55);

    @Valid @NotNull @JsonProperty
    @MinDuration(value = 1, unit = TimeUnit.SECONDS)
    private Duration reaperLockWait = Duration.seconds(5);

    @Valid @NotNull @JsonProperty
    @MinDuration(value = 1, unit = TimeUnit.SECONDS)
    private Duration nextJobAsyncTimeoutBuffer = Duration.seconds(5);
}

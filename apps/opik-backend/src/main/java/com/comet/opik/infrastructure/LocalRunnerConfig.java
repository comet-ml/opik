package com.comet.opik.infrastructure;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.dropwizard.util.Duration;
import io.dropwizard.validation.MinDuration;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.concurrent.TimeUnit;

@Data
public class LocalRunnerConfig {

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
    private Duration reaperJobInterval = Duration.seconds(60);

    @Valid @NotNull @JsonProperty
    @MinDuration(value = 1, unit = TimeUnit.SECONDS)
    private Duration reaperLockDuration = Duration.seconds(55);

    @Valid @NotNull @JsonProperty
    @MinDuration(value = 1, unit = TimeUnit.SECONDS)
    private Duration reaperLockWait = Duration.seconds(5);

    @Valid @NotNull @JsonProperty
    @MinDuration(value = 1, unit = TimeUnit.SECONDS)
    private Duration nextJobAsyncTimeoutBuffer = Duration.seconds(5);

    @Valid @NotNull @JsonProperty
    @MinDuration(value = 1, unit = TimeUnit.SECONDS)
    private Duration pairingCodeTtl = Duration.hours(1);

    @Valid @NotNull @JsonProperty
    @MinDuration(value = 1, unit = TimeUnit.SECONDS)
    private Duration pairingRunnerTtl = Duration.hours(1);

    @Valid @JsonProperty
    @Min(1) private int reaperMaxRunnersPerCycle = 100;

    @Valid @JsonProperty
    private int maxAgentsPerRunner = 50;

    @Valid @JsonProperty
    private int maxLogEntriesPerBatch = 1000;

    @Valid @JsonProperty
    @Min(1) private int bridgeMaxPendingPerRunner = 50;

    @Valid @JsonProperty
    @Min(1) private int bridgeMaxCommandsPerMinute = 600;

    @Valid @JsonProperty
    @Min(1) private int bridgeMaxWriteCommandsPerMinute = 120;

    @Valid @NotNull @JsonProperty
    @MinDuration(value = 1, unit = TimeUnit.SECONDS)
    private Duration bridgePollTimeout = Duration.seconds(30);

    @Valid @NotNull @JsonProperty
    @MinDuration(value = 1, unit = TimeUnit.SECONDS)
    private Duration bridgeDefaultCommandTimeout = Duration.seconds(30);

    @Valid @NotNull @JsonProperty
    @MinDuration(value = 1, unit = TimeUnit.SECONDS)
    private Duration bridgeMaxCommandTimeout = Duration.seconds(120);

    @Valid @NotNull @JsonProperty
    @MinDuration(value = 1, unit = TimeUnit.SECONDS)
    private Duration bridgeCompletedCommandTtl = Duration.hours(1);

    @Valid @NotNull @JsonProperty
    @MinDuration(value = 1, unit = TimeUnit.SECONDS)
    private Duration bridgeAsyncTimeoutBuffer = Duration.seconds(5);

    @Valid @JsonProperty
    @Min(1) private int bridgeMaxPayloadBytes = 1_048_576;
}

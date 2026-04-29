package com.comet.opik.infrastructure;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.dropwizard.util.Duration;
import io.dropwizard.validation.MaxDuration;
import io.dropwizard.validation.MinDuration;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;

import java.util.concurrent.TimeUnit;

@Builder(toBuilder = true)
public record ExperimentProjectMigrationConfig(
        boolean enabled,
        @Min(1) @Max(100) int workspacesPerRun,
        @Min(100) @Max(1000) int experimentBatchSize,
        @NotNull @MinDuration(value = 5, unit = TimeUnit.SECONDS) @MaxDuration(value = 1, unit = TimeUnit.HOURS) Duration interval,
        @NotNull @MinDuration(value = 0, unit = TimeUnit.SECONDS) @MaxDuration(value = 10, unit = TimeUnit.MINUTES) Duration startupDelay,
        @NotNull @MinDuration(value = 5, unit = TimeUnit.SECONDS) @MaxDuration(value = 30, unit = TimeUnit.MINUTES) Duration lockTimeout,
        @NotNull @MinDuration(value = 50, unit = TimeUnit.MILLISECONDS) @MaxDuration(value = 5, unit = TimeUnit.SECONDS) Duration lockWaitTime,
        @NotNull @MinDuration(value = 5, unit = TimeUnit.SECONDS) @MaxDuration(value = 1, unit = TimeUnit.HOURS) Duration jobTimeout) {

    @JsonIgnore
    @AssertTrue(message = "lockTimeout must be shorter than jobTimeout") public boolean isLockTimeoutShorterThanJobTimeout() {
        return lockTimeout.toJavaDuration().compareTo(jobTimeout.toJavaDuration()) < 0;
    }
}

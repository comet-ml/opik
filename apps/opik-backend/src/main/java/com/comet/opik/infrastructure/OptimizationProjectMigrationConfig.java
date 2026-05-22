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

/**
 * Configuration for the D3 optimization-project migration. Mirrors
 * {@code DatasetProjectMigrationConfig} field-for-field — same defaults survived D1/D2 in
 * production and the optimization volume is smaller still.
 *
 * <p>The {@code allowBeforeDependencies} flag overrides the hard guard that refuses to run before
 * D1 (experiments) and D2 (datasets) are fully complete. Operational ordering is documented in
 * the runbook; the override exists for integration tests and local dev where the D1/D2 backfill
 * is not run.
 */
@Builder(toBuilder = true)
public record OptimizationProjectMigrationConfig(
        boolean enabled,
        boolean allowBeforeDependencies,
        @Min(1) @Max(100) int workspacesPerRun,
        @Min(100) @Max(1000) int optimizationBatchSize,
        @NotNull @MinDuration(value = 5, unit = TimeUnit.SECONDS) @MaxDuration(value = 1, unit = TimeUnit.HOURS) Duration interval,
        @NotNull @MinDuration(value = 0, unit = TimeUnit.SECONDS) @MaxDuration(value = 10, unit = TimeUnit.MINUTES) Duration startupDelay,
        @NotNull @MinDuration(value = 5, unit = TimeUnit.SECONDS) @MaxDuration(value = 30, unit = TimeUnit.MINUTES) Duration lockTimeout,
        @NotNull @MinDuration(value = 50, unit = TimeUnit.MILLISECONDS) @MaxDuration(value = 5, unit = TimeUnit.SECONDS) Duration lockWaitTime,
        @NotNull @MinDuration(value = 5, unit = TimeUnit.SECONDS) @MaxDuration(value = 1, unit = TimeUnit.HOURS) Duration jobTimeout,
        @Min(2) @Max(8) int schedulerThreadCap,
        @Min(10) @Max(1_000) int schedulerQueuedTaskCap,
        @NotNull @MinDuration(value = 5, unit = TimeUnit.SECONDS) @MaxDuration(value = 1, unit = TimeUnit.HOURS) Duration schedulerThreadTtl) {

    @JsonIgnore
    @AssertTrue(message = "lockTimeout must be shorter than jobTimeout") public boolean isLockTimeoutShorterThanJobTimeout() {
        return lockTimeout.toJavaDuration().compareTo(jobTimeout.toJavaDuration()) < 0;
    }
}

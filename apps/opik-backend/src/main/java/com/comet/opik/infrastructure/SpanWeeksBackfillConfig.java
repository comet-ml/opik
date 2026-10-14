package com.comet.opik.infrastructure;

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
 * Tuning of the span weeks backfill job (OPIK-8706): how often it runs, how many weeks of span ids each run covers, and
 * the threads and time each run's insert may use. Whether the job is scheduled at all is
 * {@code databaseAnalyticsDataModel.spanWeeksBackfillEnabled}. Defaults live in {@code config.yml}.
 *
 * @param interval      how often the job runs, and how long a run holds the job lock: at most one chunk per interval
 *                      across the cluster. Must exceed {@code queryTimeout}, so a step ends inside its lease
 * @param weeksPerChunk weeks of span ids one chunk covers, between the install week and the plan's own week
 * @param maxThreads    ClickHouse threads one chunk may use, so the backfill stays a small share of the server
 * @param queryTimeout  ClickHouse max_execution_time for one chunk
 * @param lockWaitTime  how long a run waits for the job lock before skipping the tick
 */
@Builder(toBuilder = true)
public record SpanWeeksBackfillConfig(
        @NotNull @MinDuration(value = 10, unit = TimeUnit.SECONDS) //
        @MaxDuration(value = 1, unit = TimeUnit.HOURS) Duration interval,
        @Min(1) @Max(52) int weeksPerChunk,
        @Min(1) @Max(16) int maxThreads,
        @NotNull @MinDuration(value = 1, unit = TimeUnit.SECONDS) //
        @MaxDuration(value = 30, unit = TimeUnit.MINUTES) Duration queryTimeout,
        @NotNull @MinDuration(value = 0, unit = TimeUnit.SECONDS) //
        @MaxDuration(value = 1, unit = TimeUnit.MINUTES) Duration lockWaitTime) {

    @AssertTrue(message = "spanWeeksBackfill.interval must exceed spanWeeksBackfill.queryTimeout") public boolean isIntervalLongerThanQueryTimeout() {
        return interval == null || queryTimeout == null
                || interval.toMilliseconds() > queryTimeout.toMilliseconds();
    }
}

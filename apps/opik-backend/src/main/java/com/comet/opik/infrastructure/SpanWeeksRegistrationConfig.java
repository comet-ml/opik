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
 * Retry of a failed span weeks registration (OPIK-8706), with exponential backoff. Whether spans register at all is
 * {@code databaseAnalyticsDataModel.spanWeeksWriteEnabled}. Defaults live in {@code config.yml}.
 *
 * @param maxRetries retries after the first attempt; a repeat registration collapses in span_weeks, so retrying is safe
 * @param minBackoff first backoff; each retry doubles it, with jitter, up to {@code maxBackoff}
 * @param maxBackoff upper bound of the exponential backoff
 */
@Builder(toBuilder = true)
public record SpanWeeksRegistrationConfig(
        @Min(0) @Max(10) int maxRetries,
        @NotNull @MinDuration(value = 10, unit = TimeUnit.MILLISECONDS) //
        @MaxDuration(value = 1, unit = TimeUnit.MINUTES) Duration minBackoff,
        @NotNull @MinDuration(value = 100, unit = TimeUnit.MILLISECONDS) //
        @MaxDuration(value = 1, unit = TimeUnit.MINUTES) Duration maxBackoff) {

    @AssertTrue(message = "spanWeeksRegistration.minBackoff must not exceed spanWeeksRegistration.maxBackoff") public boolean isBackoffRangeValid() {
        return minBackoff == null || maxBackoff == null
                || minBackoff.toMilliseconds() <= maxBackoff.toMilliseconds();
    }
}

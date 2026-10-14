package com.comet.opik.infrastructure;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.dropwizard.util.Duration;
import io.dropwizard.validation.MinDuration;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.concurrent.TimeUnit;

/**
 * Retry of a failed span weeks registration (OPIK-8706), with exponential backoff. Whether spans register at all is
 * {@code databaseAnalyticsDataModel.spanWeeksWriteEnabled}.
 */
@Data
public class SpanWeeksRegistrationConfig {
    /** Retries after the first attempt; a repeat registration collapses in span_weeks, so retrying is safe. */
    @JsonProperty
    @Min(0) private int maxRetries;
    /** First backoff; each retry doubles it, with jitter, up to {@link #maxBackoff}. */
    @NotNull @JsonProperty
    @MinDuration(value = 1, unit = TimeUnit.MILLISECONDS)
    private Duration minBackoff;
    @NotNull @JsonProperty
    @MinDuration(value = 1, unit = TimeUnit.MILLISECONDS)
    private Duration maxBackoff;

    @AssertTrue(message = "spanWeeksRegistration.minBackoff must not exceed spanWeeksRegistration.maxBackoff") public boolean isBackoffRangeValid() {
        return minBackoff == null || maxBackoff == null
                || minBackoff.toMilliseconds() <= maxBackoff.toMilliseconds();
    }
}

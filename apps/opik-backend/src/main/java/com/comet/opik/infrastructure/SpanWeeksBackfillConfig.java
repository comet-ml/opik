package com.comet.opik.infrastructure;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.dropwizard.util.Duration;
import io.dropwizard.validation.MinDuration;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.concurrent.TimeUnit;

/**
 * Tuning of the span weeks backfill job (OPIK-8706), which {@code databaseAnalyticsDataModel.spanWeeksBackfillEnabled}
 * switches on.
 */
@Data
public class SpanWeeksBackfillConfig {

    /** How often the job runs; each run backfills one chunk. */
    @NotNull @JsonProperty
    @MinDuration(value = 10, unit = TimeUnit.SECONDS)
    private Duration interval;

    /** Weeks of span ids one chunk covers, between the first span's week and the plan's own week. */
    @JsonProperty
    @Min(1) private int weeksPerChunk;

    /** ClickHouse max_execution_time for one chunk, and how long a run holds the job lock. */
    @NotNull @JsonProperty
    @MinDuration(value = 1, unit = TimeUnit.MINUTES)
    private Duration queryTimeout;
}

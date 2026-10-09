package com.comet.opik.infrastructure;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.dropwizard.util.Duration;
import io.dropwizard.validation.MinDuration;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.concurrent.TimeUnit;

/**
 * The span weeks backfill job (OPIK-8706): fills span_weeks from the spans already written, then marks every project
 * as backfilled. Requires {@code databaseAnalyticsDataModel.spanWeeksWriteEnabled} live on every instance first.
 */
@Data
public class SpanWeeksBackfillConfig {

    @JsonProperty
    private boolean enabled;

    /** How often the job runs; each run backfills one chunk. */
    @NotNull @JsonProperty
    @MinDuration(value = 10, unit = TimeUnit.SECONDS)
    private Duration interval = Duration.minutes(1);

    /** Upper bound on the span rows one chunk reads; consecutive weeks are merged up to it. */
    @JsonProperty
    @Min(1) private long maxSpansPerChunk = 200_000_000L;

    /** Memory a chunk's GROUP BY may use before spilling to disk. */
    @JsonProperty
    @Min(1) private long maxBytesBeforeExternalGroupBy = 10L * 1024 * 1024 * 1024;

    /** ClickHouse max_execution_time for one chunk, and how long a run holds the job lock. */
    @NotNull @JsonProperty
    @MinDuration(value = 1, unit = TimeUnit.MINUTES)
    private Duration queryTimeout = Duration.hours(1);

    /** Projects marked as backfilled per UPDATE, once every chunk is done. */
    @JsonProperty
    @Min(1) private int projectsBatchSize = 10_000;
}

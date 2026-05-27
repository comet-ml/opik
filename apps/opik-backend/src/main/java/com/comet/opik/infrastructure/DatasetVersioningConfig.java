package com.comet.opik.infrastructure;

import io.dropwizard.util.Duration;
import io.dropwizard.validation.MaxDuration;
import io.dropwizard.validation.MinDuration;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;

import java.util.concurrent.TimeUnit;

/**
 * Runtime configuration for the dataset versioning write path.
 *
 * <p>See OPIK-6674: the {@code COPY_VERSION_ITEMS} and {@code EDIT_ITEM_VIA_SELECT_INSERT}
 * INSERT...SELECT queries on the {@code dataset_item_versions} table can return zero
 * rows when the SELECT lands on a ClickHouse replica that does not yet have the
 * source version's parts visible. The retry guard re-issues the operation with backoff
 * when zero rows are written and fails with a typed exception once attempts are exhausted,
 * instead of silently committing the truncated state.
 *
 * <p>Defaults live in {@code config.yml} / {@code config-test.yml}, not here.
 */
@Builder(toBuilder = true)
public record DatasetVersioningConfig(
        @Valid @NotNull ZeroRowsRetry zeroRowsRetry) {

    @Builder(toBuilder = true)
    public record ZeroRowsRetry(
            @Min(0) @Max(10) int maxAttempts,
            @NotNull @MinDuration(value = 0, unit = TimeUnit.MILLISECONDS) //
            @MaxDuration(value = 1, unit = TimeUnit.MINUTES) Duration minBackoff,
            @NotNull @MinDuration(value = 0, unit = TimeUnit.MILLISECONDS) //
            @MaxDuration(value = 1, unit = TimeUnit.MINUTES) Duration maxBackoff) {
    }
}

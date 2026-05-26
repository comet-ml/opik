package com.comet.opik.infrastructure;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * Runtime configuration for the dataset versioning write path.
 *
 * <p>See OPIK-6674: the {@code COPY_VERSION_ITEMS} and {@code EDIT_ITEM_VIA_SELECT_INSERT}
 * INSERT...SELECT queries on the {@code dataset_item_versions} table can return zero
 * rows when the SELECT lands on a ClickHouse replica that does not yet have the
 * source version's parts visible (cross-replica race under async_insert with no
 * quorum). When that happens, the new dataset version row is committed with a
 * truncated {@code items_total} and the customer's data is effectively lost.
 *
 * <p>This config tunes the application-side retry guard that re-issues the COPY/EDIT
 * with backoff when zero rows are written, and fails the request with a typed
 * exception once attempts are exhausted instead of silently committing the truncated
 * state.
 */
@Data
public class DatasetVersioningConfig {

    @Valid @NotNull @JsonProperty
    private ZeroRowsRetry zeroRowsRetry = new ZeroRowsRetry();

    @Data
    public static class ZeroRowsRetry {

        /**
         * Number of retry attempts when an INSERT...SELECT writes zero rows
         * while the input set was non-empty. Set to 0 to disable retries
         * (the guard still surfaces the failure as an exception). Total
         * call count is {@code maxAttempts + 1} (initial + retries).
         */
        @JsonProperty
        @Min(0) @Max(10) private int maxAttempts = 3;

        /**
         * Minimum backoff between retries, in milliseconds. The actual delay
         * grows exponentially from this value up to {@link #maxBackoffMillis}.
         */
        @JsonProperty
        @Min(0) @Max(60000) private long minBackoffMillis = 100;

        /**
         * Maximum backoff between retries, in milliseconds. Caps the exponential
         * growth of the per-attempt delay.
         */
        @JsonProperty
        @Min(0) @Max(60000) private long maxBackoffMillis = 1000;
    }
}

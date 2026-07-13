package com.comet.opik.infrastructure;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.Duration;
import java.util.List;

@Data
public class PartitionMetricsConfig {

    @JsonProperty
    private boolean enabled = false;

    /**
     * How often the partition-health poll runs (seconds). The poll reads {@code system.parts}
     * metadata plus a lightweight {@code _row_exists} mask scan per LWD table; on a large cluster
     * a full cycle costs a few seconds and a few GiB of highly compressible column reads, so 5 min
     * is a safe default. A distributed lock ensures a single instance polls per interval.
     */
    @JsonProperty
    @Min(30) @Max(3600) private int intervalSeconds = 300;

    /**
     * Tables to scan for lightweight-deleted (LWD-masked) rows via
     * {@code SELECT count() WHERE _row_exists = 0 SETTINGS apply_deleted_mask = 0}. Restricted to
     * the high-volume append/delete tables to avoid scanning small config tables every cycle.
     * Names are validated as plain identifiers before interpolation.
     */
    @NotNull @JsonProperty
    private List<String> lwdTables = List.of("traces", "spans");

    /** Derived: interval between polls. */
    public Duration getInterval() {
        return Duration.ofSeconds(intervalSeconds);
    }
}

package com.comet.opik.infrastructure;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.dropwizard.util.Duration;
import io.dropwizard.validation.MaxDuration;
import io.dropwizard.validation.MinDuration;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Data
public class PartitionMetricsConfig {

    @JsonProperty
    private boolean enabled;

    /**
     * How often the partition-health poll runs. The poll reads {@code system.parts} metadata plus a
     * lightweight {@code _row_exists} mask scan per LWD table; on a large cluster a full cycle costs
     * a few seconds and a few GiB of highly compressible column reads, so 5 min is a safe default.
     * A distributed lock ensures a single instance polls per interval.
     */
    @NotNull @JsonProperty
    @MinDuration(value = 30, unit = TimeUnit.SECONDS)
    @MaxDuration(value = 1, unit = TimeUnit.HOURS)
    private Duration interval;

    /**
     * Comma-separated tables to scan for lightweight-deleted (LWD-masked) rows via
     * {@code SELECT count() WHERE _row_exists = 0 SETTINGS apply_deleted_mask = 0}. Restricted to
     * the high-volume append/delete tables to avoid scanning small config tables every cycle.
     *
     * <p>Stored as a scalar rather than a YAML list so it binds cleanly from a comma-separated
     * environment override (Dropwizard substitutes {@code ${...}} into the raw YAML before parsing,
     * so a comma-separated env value cannot bind to a {@code List}). {@link #getLwdTables()} splits,
     * strips and drops blanks; individual names are validated as plain identifiers at the point of
     * interpolation.
     */
    @NotNull @JsonProperty
    private String lwdTables;

    /** Derived: the parsed, stripped, blank-free list of LWD tables. */
    public List<String> getLwdTables() {
        return Arrays.stream(lwdTables.split(","))
                .map(String::strip)
                .filter(table -> !table.isEmpty())
                .toList();
    }
}

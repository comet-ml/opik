package com.comet.opik.infrastructure;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.dropwizard.util.Duration;
import io.dropwizard.validation.MaxDuration;
import io.dropwizard.validation.MinDuration;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.concurrent.TimeUnit;

/** The free-form SQL post-run check: whether it runs, whether it blocks, and how it reads {@code system.query_log}. */
@Data
public class FreeFormSqlPostRunCheckConfig {

    public enum Mode {
        /** Neither the post-run check nor the scalar subquery rejection runs. */
        @JsonProperty("off")
        OFF,
        /** Both run and report (logs and metrics) but never affect the request; the post-run check runs after it. */
        @JsonProperty("audit")
        AUDIT,
        /** Scalar subqueries reading a table are rejected, and results are withheld unless the check passes. */
        @JsonProperty("enforce")
        ENFORCE
    }

    @JsonProperty
    private @NotNull Mode mode;

    /**
     * The wait before each read of the query's log entries. ClickHouse flushes query_log on its own interval (7.5 s
     * by default) on every node, so a wait longer than that finds every entry written without forcing a flush.
     */
    @JsonProperty
    private @NotNull @MinDuration(value = 0, unit = TimeUnit.MILLISECONDS) @MaxDuration(value = 60, unit = TimeUnit.SECONDS) Duration logReadDelay;

    /** Reads of the query's log entries before the check reports them missing. */
    @JsonProperty
    private @Min(1) @Max(10) int maxLogReadAttempts;
}

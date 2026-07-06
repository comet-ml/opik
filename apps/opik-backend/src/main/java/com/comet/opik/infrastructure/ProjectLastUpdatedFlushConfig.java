package com.comet.opik.infrastructure;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.dropwizard.util.Duration;
import io.dropwizard.validation.MaxDuration;
import io.dropwizard.validation.MinDuration;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.concurrent.TimeUnit;

/**
 * Config for buffering {@code projects.last_updated_trace_at} updates in Redis and flushing them to MySQL
 * periodically, instead of writing MySQL synchronously on every {@code TracesCreated}/{@code TracesUpdated} event.
 * <p>
 * The synchronous per-event write funnels every trace ingestion into an {@code UPDATE projects ... WHERE id = ?}
 * against a single hot row, producing lock contention (deadlock / lock-wait, surfaced as
 * {@code MySQLTransactionRollbackException}) and connection churn on the ingestion path. Buffering the per-project
 * maximum timestamp in a Redis ZSET and flushing it under a distributed lock collapses that to one batched write
 * per project per interval from a single flusher.
 * <p>
 * See {@code ProjectLastUpdatedFlushService} and {@code ProjectLastUpdatedFlushJob}.
 */
@Data
public class ProjectLastUpdatedFlushConfig {

    public static final String PENDING_SET_KEY = "project:last-updated-trace:pending";
    // Snapshot the live buffer is atomically renamed to while a flush drains it (renamenx), so writers keep
    // populating PENDING_SET_KEY and the drain owns its data exclusively.
    public static final String FLUSHING_SET_KEY = "project:last-updated-trace:flushing";
    public static final String MEMBER_SEPARATOR = ":";

    // When disabled, ProjectEventListener writes MySQL synchronously (legacy behavior).
    @JsonProperty
    private boolean enabled = false;

    @Valid @JsonProperty
    @NotNull @MinDuration(value = 1, unit = TimeUnit.SECONDS)
    private Duration jobInterval = Duration.seconds(30);

    // Kept below jobInterval so the hold-until-expiry lock releases naturally before the next cycle.
    @Valid @JsonProperty
    @NotNull @MinDuration(value = 1, unit = TimeUnit.SECONDS)
    private Duration jobLockTime = Duration.seconds(25);

    @Valid @JsonProperty
    @NotNull @MaxDuration(value = 5, unit = TimeUnit.SECONDS)
    @MinDuration(value = 100, unit = TimeUnit.MILLISECONDS)
    private Duration jobLockWaitTime = Duration.milliseconds(500);

    @Valid @JsonProperty
    @Min(10) @Max(5000) private int jobBatchSize = 500;
}

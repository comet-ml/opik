package com.comet.opik.infrastructure;

import io.dropwizard.util.Duration;
import io.dropwizard.validation.MaxDuration;
import io.dropwizard.validation.MinDuration;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;

import java.util.concurrent.TimeUnit;

/**
 * Configuration for the stalled Optimization Studio run reaper (OPIK-7159).
 * <p>
 * A Studio run's status is only ever advanced by the Python optimizer worker calling back to the API
 * (mark_running / mark_completed / mark_error). If the worker never runs (worker down, Redis/queue
 * unreachable, job lost) or crashes before it can report, nothing transitions the run off the
 * {@code INITIALIZED} value the backend sets at creation, and it spins forever with no error surfaced.
 * This reaper is the environment-independent safety net: it periodically finds studio runs that have
 * been stuck in a non-terminal status past a threshold and transitions them to {@code ERROR} with a
 * reason, so a run can never remain stuck indefinitely.
 *
 * @param enabled whether the reaper job is scheduled.
 * @param startupDelay delay before the first reaper run after startup, to avoid false positives while
 *        workers are still warming up and consuming the backlog.
 * @param jobInterval how often the reaper runs. A single instance runs per cycle (distributed lock with
 *        hold-until-expiry), so the query cost stays negligible.
 * @param initializedTimeout a run stuck in {@code INITIALIZED} longer than this is transitioned to
 *        {@code ERROR}. The worker is expected to call mark_running within seconds of picking the job
 *        up, so this can be short — but it is kept comfortably above normal queue latency to avoid
 *        killing a run that is merely waiting behind a backlog.
 * @param runningTimeout a run stuck in {@code RUNNING} longer than this is transitioned to {@code ERROR}.
 *        There is no per-progress heartbeat on the optimization row (last_updated_at only advances on a
 *        status change), so this MUST be set above the worker's maximum execution timeout
 *        ({@code OPTSTUDIO_EXECUTION_TIMEOUT}, default 24h) plus a buffer, otherwise a legitimately long
 *        run would be reaped mid-flight.
 * @param lockDuration lock TTL, held until expiry, that suppresses other instances from reconciling until
 *        it elapses. MUST be kept below {@link #jobInterval()} (the lock is held until expiry, so a
 *        lockDuration &gt;= jobInterval would make every other scheduled tick a no-op and silently halve
 *        the effective cadence). Marking a run {@code ERROR} is idempotent, so an occasional overlap
 *        across instances is harmless.
 * @param batchSize maximum number of stalled runs reconciled per cycle, so a large backlog is drained
 *        over several cycles rather than in one burst.
 */
@Builder(toBuilder = true)
public record OptimizationStalledReaperConfig(
        boolean enabled,
        @NotNull @MinDuration(value = 0, unit = TimeUnit.SECONDS) @MaxDuration(value = 30, unit = TimeUnit.MINUTES) Duration startupDelay,
        @NotNull @MinDuration(value = 1, unit = TimeUnit.MINUTES) @MaxDuration(value = 6, unit = TimeUnit.HOURS) Duration jobInterval,
        @NotNull @MinDuration(value = 1, unit = TimeUnit.MINUTES) @MaxDuration(value = 24, unit = TimeUnit.HOURS) Duration initializedTimeout,
        @NotNull @MinDuration(value = 1, unit = TimeUnit.HOURS) @MaxDuration(value = 7, unit = TimeUnit.DAYS) Duration runningTimeout,
        @NotNull @MinDuration(value = 1, unit = TimeUnit.MINUTES) @MaxDuration(value = 1, unit = TimeUnit.HOURS) Duration lockDuration,
        @Min(1) @Max(10_000) int batchSize) {
}

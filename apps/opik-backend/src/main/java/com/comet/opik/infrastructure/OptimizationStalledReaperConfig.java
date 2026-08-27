package com.comet.opik.infrastructure;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.dropwizard.util.Duration;
import io.dropwizard.validation.MaxDuration;
import io.dropwizard.validation.MinDuration;
import jakarta.validation.constraints.AssertTrue;
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
 * @param initializedTimeout a run stuck in {@code INITIALIZED} with no <em>activity</em> for longer than
 *        this is transitioned to {@code ERROR}. The worker is expected to call mark_running within seconds
 *        of picking the job up, so this can be short — but it is kept comfortably above normal queue
 *        latency to avoid killing a run that is merely waiting behind a backlog. Note this bounds the
 *        no-status-change side only: a run that never left {@code INITIALIZED} but is writing trials or
 *        items is spared by the same progress veto {@code runningTimeout} governs, measured on the
 *        {@code runningTimeout} window (OPIK-7459) — a single failed mark_running callback must not kill a
 *        run that is actually evaluating.
 * @param runningTimeout a non-terminal run showing no <em>activity</em> for longer than this is
 *        transitioned to {@code ERROR}. Activity is liveness derived from run progress (OPIK-7459): the
 *        newest of the row's {@code last_updated_at}, the latest trial experiment's {@code created_at},
 *        and the latest experiment item's {@code created_at} — a healthy run keeps creating trial
 *        experiments and, within a trial, one experiment item per evaluated dataset item, so this no
 *        longer needs to exceed the worker's maximum execution timeout
 *        ({@code OPTSTUDIO_EXECUTION_TIMEOUT}, default 6h) and can be under an hour. It is the activity
 *        window for {@code INITIALIZED} candidates too, not only {@code RUNNING} ones. What it MUST stay
 *        above is the longest legitimate gap between progress signals, and that gap is not the
 *        steady-state item cadence but the run's <em>head start</em>: between mark_running and the first
 *        trial experiment the worker fetches and samples the dataset (up to
 *        {@code OPTSTUDIO_DATASET_SAMPLES}) and, for GEPA, builds the baseline, writing nothing. Sized
 *        below that, a slow-but-alive run is reaped before it ever produces a trial; the
 *        {@code @MinDuration} floor guards the pathological end of that (same fail-fast intent as
 *        {@link #isLockDurationBelowJobInterval()}).
 * @param runningHardTimeout absolute ceiling for a non-terminal run ({@code INITIALIZED} or
 *        {@code RUNNING}) measured from when the run was
 *        created, reaped even when trial/item writes are still arriving. This preserves the pre-OPIK-7459
 *        "a run can never stay stuck indefinitely" guarantee against a zombie worker that keeps producing
 *        rows without ever reporting a terminal status — including one whose run never left
 *        {@code INITIALIZED}, which is why the ceiling covers that status too. MUST exceed the worker's maximum execution
 *        timeout ({@code OPTSTUDIO_EXECUTION_TIMEOUT}, default 6h) plus a buffer — the {@code @MinDuration}
 *        floor is pinned to that 6h default — and MUST NOT be below {@link #runningTimeout()}
 *        (enforced by {@link #isRunningHardTimeoutAtLeastRunningTimeout()}). Measuring it from creation
 *        rather than from {@code last_updated_at} is deliberate: every write to the row refreshes that
 *        column, so a metadata PATCH or an SDK re-upsert would postpone the backstop indefinitely.
 * @param lookbackMargin added to {@code max(initializedTimeout, runningTimeout, runningHardTimeout)} to
 *        size the {@code last_updated_at >= now - window} floor of the reaper's scan. It is pure
 *        reaper-downtime insurance: a run that stalled just before the reaper became unavailable is still
 *        caught once the reaper recovers, as long as it was down for less than this margin. It does NOT
 *        need to be large to find fresh stalls (those are always within the timeout), so keep it only as
 *        big as the longest expected reaper outage — a shorter margin also tightens the skip-index granule
 *        pruning. Folding {@code runningHardTimeout} into the maximum is what keeps the floor safe: since
 *        {@code lookback >= runningHardTimeout}, any run not yet past the ceiling is younger than the
 *        floor, so it can never be missed by the scan.
 * @param lockDuration lock TTL, held until expiry, that suppresses other instances from reconciling until
 *        it elapses. MUST be kept below {@link #jobInterval()} (the lock is held until expiry, so a
 *        lockDuration &gt;= jobInterval would make every other scheduled tick a no-op and silently halve
 *        the effective cadence). Marking a run {@code ERROR} is idempotent, so an occasional overlap
 *        across instances is harmless.
 * @param batchSize maximum number of stalled runs reconciled per cycle, so a large backlog is drained
 *        over several cycles rather than in one burst.
 * @param candidateScanFactor multiple of {@link #batchSize()} bounding the candidate set the reaper query's
 *        two liveness probes fan out from. Without a bound that set is every non-terminal studio run whose
 *        row has not changed in {@code runningTimeout} — which, because {@code last_updated_at} only advances
 *        on a status change, includes every <em>healthy</em> in-flight run older than the timeout, so probe
 *        cost would scale with fleet size instead of with configuration. It is a multiple rather than
 *        {@code batchSize} itself because the ordering puts the stalest first and a healthy long run sorts
 *        alongside a dead one (the premise of the whole progress-veto design): at exactly {@code batchSize},
 *        live runs could crowd dead ones out of every pass. Raising it widens the query's reach at
 *        proportionally higher probe cost; lowering it toward 1 reintroduces that starvation risk.
 */
@Builder(toBuilder = true)
public record OptimizationStalledReaperConfig(
        boolean enabled,
        @NotNull @MinDuration(value = 0, unit = TimeUnit.SECONDS) @MaxDuration(value = 30, unit = TimeUnit.MINUTES) Duration startupDelay,
        @NotNull @MinDuration(value = 1, unit = TimeUnit.MINUTES) @MaxDuration(value = 6, unit = TimeUnit.HOURS) Duration jobInterval,
        @NotNull @MinDuration(value = 1, unit = TimeUnit.MINUTES) @MaxDuration(value = 24, unit = TimeUnit.HOURS) Duration initializedTimeout,
        @NotNull @MinDuration(value = 5, unit = TimeUnit.MINUTES) @MaxDuration(value = 7, unit = TimeUnit.DAYS) Duration runningTimeout,
        @NotNull @MinDuration(value = 6, unit = TimeUnit.HOURS) @MaxDuration(value = 30, unit = TimeUnit.DAYS) Duration runningHardTimeout,
        @NotNull @MinDuration(value = 1, unit = TimeUnit.HOURS) @MaxDuration(value = 30, unit = TimeUnit.DAYS) Duration lookbackMargin,
        @NotNull @MinDuration(value = 1, unit = TimeUnit.MINUTES) @MaxDuration(value = 1, unit = TimeUnit.HOURS) Duration lockDuration,
        @Min(1) @Max(10_000) int batchSize,
        @Min(1) @Max(1_000) int candidateScanFactor) {

    /**
     * Enforce the {@link #lockDuration()} &lt; {@link #jobInterval()} invariant at boot instead of only
     * documenting it: the lock is held until expiry, so a lockDuration &gt;= jobInterval would make every
     * other scheduled tick a no-op and silently halve the effective cadence.
     */
    @JsonIgnore
    @AssertTrue(message = "optimizationStalledReaper.lockDuration must be less than jobInterval") public boolean isLockDurationBelowJobInterval() {
        return lockDuration == null || jobInterval == null
                || lockDuration.toMilliseconds() < jobInterval.toMilliseconds();
    }

    /**
     * The hard ceiling must not undercut the progress timeout — otherwise the ceiling, which ignores the
     * progress signal, would reap healthy runs before the progress-based check even gets a say.
     */
    @JsonIgnore
    @AssertTrue(message = "optimizationStalledReaper.runningHardTimeout must not be less than runningTimeout") public boolean isRunningHardTimeoutAtLeastRunningTimeout() {
        return runningHardTimeout == null || runningTimeout == null
                || runningHardTimeout.toMilliseconds() >= runningTimeout.toMilliseconds();
    }
}

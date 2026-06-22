package com.comet.opik.infrastructure;

import io.dropwizard.util.Duration;
import io.dropwizard.validation.MaxDuration;
import io.dropwizard.validation.MinDuration;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;

import java.util.concurrent.TimeUnit;

/**
 * Configuration for the orphaned-consumer reaper (OPIK-6982).
 * <p>
 * Each backend process registers a unique {@code consumer-<group>-<UUID>} that Redis only removes on graceful
 * shutdown ({@code XGROUP DELCONSUMER}). Non-graceful exits (OOMKill, SIGKILL, crash, node eviction) leak the
 * consumer permanently, so the online-scoring/stream consumer groups accumulate orphans across deploys and crashes.
 * The reaper periodically removes consumers that are both idle beyond {@link #idleThreshold()} and have no pending
 * entries (so it never drops un-acked work that {@code XAUTOCLAIM} would otherwise reclaim).
 *
 * @param enabled whether the reaper job is scheduled.
 * @param startupDelay delay before the first reaper run after startup, to avoid reaping during warm-up and reduce
 *        the chance of false positives.
 * @param jobInterval how often the reaper runs. A single instance runs per cycle (distributed lock with
 *        hold-until-expiry), so a low frequency keeps the {@code XINFO GROUPS}/{@code XINFO CONSUMERS} cost negligible.
 * @param idleThreshold consumers idle longer than this (and with no pending entries) are deleted. Live consumers
 *        reset their idle time on every {@code XREADGROUP} (even empty long-poll returns), so their idle stays in the
 *        seconds range; the configured value is deliberately far above that to avoid reaping an active consumer.
 * @param lockDuration lock TTL, held until expiry, that suppresses other instances from reaping until it elapses. It
 *        does NOT cancel an in-flight reap pass ({@code bestEffortLock} does not bound the action) — the pass runs to
 *        completion even if the lease expires first. Kept well below {@link #jobInterval()}; if the lease expires
 *        mid-cycle and another instance runs an extra pass, that is harmless since {@code XGROUP DELCONSUMER} is
 *        idempotent and a pass is light.
 */
@Builder(toBuilder = true)
public record StreamConsumerReaperConfig(
        boolean enabled,
        @NotNull @MinDuration(value = 0, unit = TimeUnit.SECONDS) @MaxDuration(value = 10, unit = TimeUnit.MINUTES) Duration startupDelay,
        @NotNull @MinDuration(value = 5, unit = TimeUnit.MINUTES) @MaxDuration(value = 24, unit = TimeUnit.HOURS) Duration jobInterval,
        @NotNull @MinDuration(value = 1, unit = TimeUnit.HOURS) @MaxDuration(value = 30, unit = TimeUnit.DAYS) Duration idleThreshold,
        @NotNull @MinDuration(value = 1, unit = TimeUnit.MINUTES) @MaxDuration(value = 1, unit = TimeUnit.HOURS) Duration lockDuration) {
}

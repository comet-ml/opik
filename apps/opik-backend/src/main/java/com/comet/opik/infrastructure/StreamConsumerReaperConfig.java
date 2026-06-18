package com.comet.opik.infrastructure;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.dropwizard.util.Duration;
import io.dropwizard.validation.MinDuration;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.concurrent.TimeUnit;

/**
 * Configuration for the orphaned-consumer reaper (OPIK-6982).
 * <p>
 * Each backend process registers a unique {@code consumer-<group>-<UUID>} that Redis only removes on graceful
 * shutdown ({@code XGROUP DELCONSUMER}). Non-graceful exits (OOMKill, SIGKILL, crash, node eviction) leak the
 * consumer permanently, so the online-scoring/stream consumer groups accumulate orphans across deploys and crashes.
 * The reaper periodically removes consumers that are both idle beyond {@link #getIdleThreshold()} and have no
 * pending entries (so it never drops un-acked work that {@code XAUTOCLAIM} would otherwise reclaim).
 */
@Data
public class StreamConsumerReaperConfig {

    @Valid @JsonProperty
    private boolean enabled = true;

    /**
     * How often the reaper runs. A single instance runs per cycle (distributed lock with hold-until-expiry), so
     * a low frequency keeps the {@code XINFO GROUPS}/{@code XINFO CONSUMERS} cost negligible.
     */
    @Valid @NotNull @JsonProperty
    @MinDuration(value = 1, unit = TimeUnit.MINUTES)
    private Duration jobInterval = Duration.hours(1);

    /**
     * Consumers idle longer than this (and with no pending entries) are deleted. Live consumers reset their idle
     * time on every {@code XREADGROUP} (even empty long-poll returns), so their idle stays in the seconds range;
     * the default is deliberately far above that to avoid reaping an active consumer.
     */
    @Valid @NotNull @JsonProperty
    @MinDuration(value = 1, unit = TimeUnit.MINUTES)
    private Duration idleThreshold = Duration.days(1);

    /**
     * Lock TTL, held until expiry, that suppresses concurrent fleet-wide runs. Also bounds how long a single reap
     * pass may run. A reap pass is light (per-stream {@code XINFO GROUPS}/{@code XINFO CONSUMERS} + a few
     * {@code XGROUP DELCONSUMER}), so this is well below {@link #getJobInterval()}; an occasional extra pass within
     * a cycle is harmless since {@code XGROUP DELCONSUMER} is idempotent.
     */
    @Valid @NotNull @JsonProperty
    @MinDuration(value = 1, unit = TimeUnit.SECONDS)
    private Duration lockDuration = Duration.minutes(10);
}

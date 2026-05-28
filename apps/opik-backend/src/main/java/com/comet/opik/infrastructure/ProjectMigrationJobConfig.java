package com.comet.opik.infrastructure;

import io.dropwizard.util.Duration;

/**
 * Shared shape for the V1 → V2 project-migration Quartz job configs (experiments, datasets,
 * optimizations, prompts, automation rules, alerts). Surfaces the common fields the
 * {@link com.comet.opik.api.resources.v1.jobs.AbstractProjectMigrationJob} abstract base and the
 * {@code OpikGuiceyLifecycleEventListener} scheduling helper need to drive a recurring migration
 * cycle: the enabled flag, the Quartz schedule, the distributed-lock timeouts, and the per-cycle
 * hard timeout.
 *
 * <p>Entity-specific records add their own per-cycle parameters
 * ({@code workspacesPerRun}, batch sizes, dependency overrides, …) that the concrete migration
 * services read directly off the concrete record.
 */
public interface ProjectMigrationJobConfig {

    /** Master switch — short-circuits the cycle when false. */
    boolean enabled();

    /** How often the Quartz schedule fires. */
    Duration interval();

    /** Delay between application start and the first cycle. */
    Duration startupDelay();

    /** Distributed-lock TTL — must be shorter than {@link #jobTimeout()}. */
    Duration lockTimeout();

    /** Max time to wait for the distributed lock when another replica holds it. */
    Duration lockWaitTime();

    /** Per-cycle hard timeout — safety net for stuck cycles. */
    Duration jobTimeout();

    /** Max threads in the dedicated reactor scheduler for this migration. */
    int schedulerThreadCap();

    /** Max tasks queued in the dedicated reactor scheduler before backpressure rejects new work. */
    int schedulerQueuedTaskCap();

    /** Idle-thread TTL for the dedicated reactor scheduler — controls thread reaping during idle. */
    Duration schedulerThreadTtl();
}

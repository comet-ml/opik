package com.comet.opik.infrastructure;

import io.dropwizard.util.Duration;

/**
 * Shared shape for the V1 → V2 project-migration Quartz job configs (experiments, datasets,
 * optimizations, prompts). Surfaces only the fields the
 * {@link com.comet.opik.api.resources.v1.jobs.AbstractProjectMigrationJob} abstract base needs to
 * drive a cycle — the enabled flag, the distributed-lock timeouts, and the per-cycle hard timeout.
 *
 * <p>Entity-specific records add their own scheduling fields ({@code interval},
 * {@code startupDelay}, {@code schedulerThreadCap}, …) that {@code OpikGuiceyLifecycleEventListener}
 * reads independently when wiring the Quartz schedule.
 */
public interface ProjectMigrationJobConfig {

    /** Master switch — short-circuits the cycle when false. */
    boolean enabled();

    /** Distributed-lock TTL — must be shorter than {@link #jobTimeout()}. */
    Duration lockTimeout();

    /** Max time to wait for the distributed lock when another replica holds it. */
    Duration lockWaitTime();

    /** Per-cycle hard timeout — safety net for stuck cycles. */
    Duration jobTimeout();
}

package com.comet.opik.domain;

import com.comet.opik.infrastructure.ProjectMigrationJobConfig;
import io.dropwizard.lifecycle.Managed;
import lombok.extern.slf4j.Slf4j;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/**
 * Shared {@link Managed} lifecycle for the V1 → V2 project-migration services (experiments,
 * datasets, optimizations, prompts). Each subclass keeps its entity-specific cycle logic; this base
 * just owns the dedicated reactor {@link Scheduler} that isolates the migration's blocking I/O
 * from the shared {@code boundedElastic} pool.
 *
 * <p>The scheduler is built from {@link ProjectMigrationJobConfig#schedulerThreadCap()},
 * {@link ProjectMigrationJobConfig#schedulerQueuedTaskCap()}, and
 * {@link ProjectMigrationJobConfig#schedulerThreadTtl()}. Subclasses get the live scheduler via
 * {@link #migrationScheduler()} (it stays {@code null} until {@link #start()} runs, mirroring
 * the previous per-service implementation).
 */
@Slf4j
public abstract class AbstractProjectMigrationService implements Managed {

    private volatile Scheduler migrationScheduler;

    /** Entity-specific name for the dedicated reactor scheduler (e.g., {@code "optimization-project-migration-service"}). */
    protected abstract String schedulerName();

    /**
     * Entity-specific migration config exposing the scheduler thread cap / queue cap / TTL knobs.
     * Subclasses typically just return the same record they hold as {@code config}.
     */
    protected abstract ProjectMigrationJobConfig jobConfig();

    /** Returns the dedicated reactor scheduler; {@code null} until {@link #start()} runs. */
    protected final Scheduler migrationScheduler() {
        return migrationScheduler;
    }

    @Override
    public void start() {
        if (migrationScheduler == null) {
            var cfg = jobConfig();
            migrationScheduler = Schedulers.newBoundedElastic(
                    cfg.schedulerThreadCap(),
                    cfg.schedulerQueuedTaskCap(),
                    schedulerName(),
                    (int) cfg.schedulerThreadTtl().toJavaDuration().toSeconds(),
                    true);
            log.info(
                    "Initialized migration scheduler, name='{}', threadCap='{}', queuedTaskCap='{}', threadTtl='{}'",
                    schedulerName(), cfg.schedulerThreadCap(), cfg.schedulerQueuedTaskCap(),
                    cfg.schedulerThreadTtl());
        }
    }

    @Override
    public void stop() {
        if (migrationScheduler != null && !migrationScheduler.isDisposed()) {
            migrationScheduler.dispose();
            log.info("Migration scheduler disposed, name='{}'", schedulerName());
        }
    }
}

package com.comet.opik.api.resources.v1.jobs;

import com.comet.opik.domain.ProjectService;
import com.comet.opik.infrastructure.ProjectLastUpdatedFlushConfig;
import com.comet.opik.infrastructure.lock.LockService;
import io.dropwizard.jobs.Job;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.InterruptableJob;
import org.quartz.JobExecutionContext;
import org.quartz.UnableToInterruptJobException;
import reactor.core.publisher.Mono;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.util.concurrent.atomic.AtomicBoolean;

import static com.comet.opik.infrastructure.lock.LockService.Lock;

/**
 * Scheduled job that flushes the Redis-buffered {@code projects.last_updated_trace_at} maxima to MySQL.
 *
 * <p>Runs periodically (default 30s, {@code projectLastUpdatedFlush.jobInterval}) under a best-effort distributed
 * lock held until expiry, so a single instance flushes per cycle across the cluster. Draining and writing are
 * idempotent (the MySQL write only moves the marker forward), so a lost lock or overlapping run is safe.
 */
@Slf4j
@Singleton
@DisallowConcurrentExecution
public class ProjectLastUpdatedFlushJob extends Job implements InterruptableJob {

    private static final Lock FLUSH_LOCK = new Lock("project_last_updated_flush_job:scan_lock");

    private final AtomicBoolean interrupted = new AtomicBoolean(false);
    private final ProjectLastUpdatedFlushConfig config;
    private final ProjectService projectService;
    private final LockService lockService;

    @Inject
    public ProjectLastUpdatedFlushJob(
            @NonNull @Config("projectLastUpdatedFlush") ProjectLastUpdatedFlushConfig config,
            @NonNull ProjectService projectService,
            @NonNull LockService lockService) {
        this.config = config;
        this.projectService = projectService;
        this.lockService = lockService;
    }

    @Override
    public void doJob(JobExecutionContext context) {
        if (!config.isEnabled() || interrupted.get()) {
            return;
        }

        lockService.bestEffortLock(
                FLUSH_LOCK,
                Mono.defer(projectService::flushLastUpdatedTraces)
                        .doOnNext(count -> {
                            if (count > 0) {
                                log.info("Project last-updated flush wrote '{}' project markers", count);
                            }
                        })
                        .then(),
                Mono.fromRunnable(() -> log.debug(
                        "Another instance is flushing project last-updated markers, skipping")),
                config.getJobLockTime().toJavaDuration(),
                config.getJobLockWaitTime().toJavaDuration(),
                true)
                .subscribe(
                        __ -> {
                        },
                        error -> log.error("Project last-updated flush job failed", error));
    }

    @Override
    public void interrupt() throws UnableToInterruptJobException {
        interrupted.set(true);
        log.info("ProjectLastUpdatedFlushJob interrupted");
    }
}

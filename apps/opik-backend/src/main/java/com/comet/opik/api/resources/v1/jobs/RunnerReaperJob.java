package com.comet.opik.api.resources.v1.jobs;

import com.comet.opik.domain.RunnerService;
import com.comet.opik.infrastructure.RunnerConfig;
import com.comet.opik.infrastructure.lock.LockService;
import io.dropwizard.jobs.Job;
import io.dropwizard.jobs.annotations.Every;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.InterruptableJob;
import org.quartz.JobExecutionContext;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicBoolean;

import static com.comet.opik.infrastructure.lock.LockService.Lock;

@Slf4j
@Singleton
@DisallowConcurrentExecution
@Every("60s")
public class RunnerReaperJob extends Job implements InterruptableJob {

    private static final Lock REAPER_LOCK = new Lock("runner_reaper", RunnerReaperJob.class.getSimpleName());

    private final RunnerService runnerService;
    private final LockService lockService;
    private final RunnerConfig runnerConfig;

    private final AtomicBoolean interrupted = new AtomicBoolean(false);

    @Inject
    public RunnerReaperJob(@NonNull RunnerService runnerService,
            @NonNull LockService lockService,
            @NonNull RunnerConfig runnerConfig) {
        this.runnerService = runnerService;
        this.lockService = lockService;
        this.runnerConfig = runnerConfig;
    }

    @Override
    public void doJob(JobExecutionContext context) {
        if (interrupted.get()) {
            log.info("Runner reaper job interrupted before execution, skipping");
            return;
        }

        if (!runnerConfig.isEnabled()) {
            return;
        }

        lockService.bestEffortLock(
                REAPER_LOCK,
                Mono.fromRunnable(() -> runnerService.reapDeadRunners()),
                Mono.fromRunnable(() -> log.info("Could not acquire reaper lock, skipping")),
                runnerConfig.getReaperLockDuration().toJavaDuration(),
                runnerConfig.getReaperLockWait().toJavaDuration())
                .subscribe(
                        __ -> log.info("Runner reaper completed"),
                        error -> log.error("Runner reaper failed", error));
    }

    @Override
    public void interrupt() {
        interrupted.set(true);
        log.info("Runner reaper job interrupted");
    }
}

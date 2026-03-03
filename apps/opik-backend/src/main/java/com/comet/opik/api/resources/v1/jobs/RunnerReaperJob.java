package com.comet.opik.api.resources.v1.jobs;

import com.comet.opik.domain.RunnerService;
import com.comet.opik.infrastructure.RunnerConfig;
import com.comet.opik.infrastructure.lock.LockService;
import io.dropwizard.jobs.Job;
import io.dropwizard.jobs.annotations.Every;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.JobExecutionContext;
import reactor.core.publisher.Mono;

import java.time.Duration;

import static com.comet.opik.infrastructure.lock.LockService.Lock;

@Slf4j
@Singleton
@DisallowConcurrentExecution
@Every("60s")
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class RunnerReaperJob extends Job {

    private static final Lock REAPER_LOCK = new Lock("runner-reaper");

    private final @NonNull RunnerService runnerService;
    private final @NonNull LockService lockService;
    private final @NonNull RunnerConfig runnerConfig;

    @Override
    public void doJob(JobExecutionContext context) {
        if (!runnerConfig.isEnabled()) {
            return;
        }

        lockService.bestEffortLock(
                REAPER_LOCK,
                Mono.fromRunnable(() -> runnerService.reapDeadRunners()),
                Mono.fromRunnable(() -> log.info("Could not acquire reaper lock, skipping")),
                Duration.ofSeconds(runnerConfig.getReaperLockDurationSeconds()),
                Duration.ofSeconds(runnerConfig.getReaperLockWaitSeconds()))
                .subscribe(
                        __ -> log.info("Runner reaper completed"),
                        error -> log.error("Runner reaper failed", error));
    }
}

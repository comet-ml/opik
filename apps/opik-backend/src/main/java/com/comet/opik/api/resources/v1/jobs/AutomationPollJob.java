package com.comet.opik.api.resources.v1.jobs;

import com.comet.opik.domain.automations.AutomationPollService;
import com.comet.opik.infrastructure.AutomationEventsConfig;
import com.comet.opik.infrastructure.FeatureFlags;
import com.comet.opik.infrastructure.lock.LockService;
import com.comet.opik.infrastructure.lock.LockService.Lock;
import io.dropwizard.jobs.Job;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.InterruptableJob;
import org.quartz.JobExecutionContext;
import org.quartz.UnableToInterruptJobException;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Ticks the automation poll: counted triggers, parked runs on optimizations, and wait timeouts. One
 * replica per tick, under a best-effort lock, as the routing flush job does.
 */
@Slf4j
@Singleton
@DisallowConcurrentExecution
public class AutomationPollJob extends Job implements InterruptableJob {

    private static final Lock POLL_LOCK = new Lock("automation_poll_job:scan_lock");

    private final AtomicBoolean interrupted = new AtomicBoolean(false);
    private final AtomicReference<Disposable> subscription = new AtomicReference<>();
    private final AutomationEventsConfig config;
    private final FeatureFlags featureFlags;
    private final AutomationPollService pollService;
    private final LockService lockService;

    @Inject
    public AutomationPollJob(@NonNull @Config("automationEvents") AutomationEventsConfig config,
            @NonNull FeatureFlags featureFlags, @NonNull AutomationPollService pollService,
            @NonNull LockService lockService) {
        this.config = config;
        this.featureFlags = featureFlags;
        this.pollService = pollService;
        this.lockService = lockService;
    }

    @Override
    public void doJob(JobExecutionContext context) {
        if (!featureFlags.isAutomationsEnabled() || interrupted.get()) {
            return;
        }
        Duration interval = config.getPollInterval().toJavaDuration();
        Disposable disposable = lockService.bestEffortLock(
                POLL_LOCK,
                pollService.tick(),
                Mono.fromRunnable(() -> log.debug("Another instance is polling automations, skipping")),
                interval.dividedBy(2),
                Duration.ofMillis(300),
                true)
                .subscribe(
                        __ -> {
                        },
                        error -> log.error("Automation poll job failed", error));
        subscription.set(disposable);
    }

    @Override
    public void interrupt() throws UnableToInterruptJobException {
        interrupted.set(true);
        Disposable disposable = subscription.getAndSet(null);
        if (disposable != null && !disposable.isDisposed()) {
            disposable.dispose();
        }
    }
}

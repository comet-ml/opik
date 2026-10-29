package com.comet.opik.api.resources.v1.jobs;

import com.comet.opik.domain.ailabels.AiLabelsTickService;
import com.comet.opik.infrastructure.AiLabelsConfig;
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
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static com.comet.opik.infrastructure.lock.LockService.Lock;

/**
 * Schedules the ai-labels tick. Every pod fires it every {@code aiLabels.tickInterval}; only the pod holding the leader
 * lock works, and releases it when its tick ends. The tick itself is {@link AiLabelsTickService#tick(Instant)}.
 */
@Slf4j
@Singleton
@DisallowConcurrentExecution
public class AiLabelsJob extends Job implements InterruptableJob {

    private static final Lock LEADER_LOCK = new Lock("ai_labels:leader");

    private final AtomicBoolean interrupted = new AtomicBoolean(false);
    private final AtomicReference<Disposable> subscription = new AtomicReference<>();
    private final AiLabelsConfig config;
    private final AiLabelsTickService tickService;
    private final LockService lockService;

    @Inject
    public AiLabelsJob(@NonNull @Config("aiLabels") AiLabelsConfig config, @NonNull AiLabelsTickService tickService,
            @NonNull LockService lockService) {
        this.config = config;
        this.tickService = tickService;
        this.lockService = lockService;
    }

    @Override
    public void doJob(JobExecutionContext context) {
        if (!config.isEnabled() || interrupted.get()) {
            return;
        }
        var start = Instant.now();
        Disposable disposable = lockService.bestEffortLock(
                LEADER_LOCK,
                Mono.fromRunnable(() -> tickService.tick(start)),
                Mono.fromRunnable(() -> log.debug("Another instance is running the ai-labels tick, skipping")),
                tickService.leaderLease(),
                Duration.ZERO)
                .subscribe(
                        __ -> {
                        },
                        error -> log.error("ai-labels tick failed", error));
        subscription.set(disposable);
    }

    @Override
    public void interrupt() throws UnableToInterruptJobException {
        interrupted.set(true);
        Optional.ofNullable(subscription.getAndSet(null)).ifPresent(Disposable::dispose);
        log.info("AiLabelsJob interrupted, cancelling any in-flight tick");
    }
}

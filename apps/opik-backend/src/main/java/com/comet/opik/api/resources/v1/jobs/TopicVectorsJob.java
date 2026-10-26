package com.comet.opik.api.resources.v1.jobs;

import com.comet.opik.domain.topics.TopicVectorsTickService;
import com.comet.opik.infrastructure.TopicsConfig;
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
 * Schedules the topic vectors tick. Every pod fires it every {@code topics.vectors.tickInterval}; only the pod holding
 * the leader lock works, and releases it when its tick ends. The tick itself is
 * {@link TopicVectorsTickService#tick(Instant)}.
 */
@Slf4j
@Singleton
@DisallowConcurrentExecution
public class TopicVectorsJob extends Job implements InterruptableJob {

    private static final Lock LEADER_LOCK = new Lock("topics:vectors:leader");

    private final AtomicBoolean interrupted = new AtomicBoolean(false);
    private final AtomicReference<Disposable> subscription = new AtomicReference<>();
    private final TopicsConfig config;
    private final TopicVectorsTickService tickService;
    private final LockService lockService;

    @Inject
    public TopicVectorsJob(@NonNull @Config("topics") TopicsConfig config,
            @NonNull TopicVectorsTickService tickService, @NonNull LockService lockService) {
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
                Mono.fromRunnable(() -> log.debug("Another instance is running the topic vectors tick, skipping")),
                tickService.leaderLease(),
                Duration.ZERO)
                .subscribe(
                        __ -> {
                        },
                        error -> log.error("Topic vectors tick failed", error));
        subscription.set(disposable);
    }

    @Override
    public void interrupt() throws UnableToInterruptJobException {
        interrupted.set(true);
        Optional.ofNullable(subscription.getAndSet(null)).ifPresent(Disposable::dispose);
        log.info("TopicVectorsJob interrupted, cancelling any in-flight tick");
    }
}

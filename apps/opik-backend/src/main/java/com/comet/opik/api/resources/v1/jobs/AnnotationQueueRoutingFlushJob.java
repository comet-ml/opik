package com.comet.opik.api.resources.v1.jobs;

import com.comet.opik.domain.AnnotationQueueRoutingBufferService;
import com.comet.opik.infrastructure.AnnotationQueueRoutingConfig;
import com.comet.opik.infrastructure.FeatureFlags;
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

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static com.comet.opik.infrastructure.lock.LockService.Lock;

/**
 * Schedules the periodic flush of the annotation queue routing buffer to its stream.
 *
 * <p>Orchestration only: runs every {@code annotationQueueRouting.jobInterval} under a best-effort
 * distributed lock held until expiry, so one replica flushes per cycle across the cluster, and keeps the
 * subscription so an interrupt cancels an in-flight flush rather than just the schedule. The flush itself,
 * Redis access included, is {@link AnnotationQueueRoutingBufferService#flush()}.
 */
@Slf4j
@Singleton
@DisallowConcurrentExecution
public class AnnotationQueueRoutingFlushJob extends Job implements InterruptableJob {

    private static final Lock FLUSH_LOCK = new Lock("annotation_queue_routing_flush_job:scan_lock");

    private final AtomicBoolean interrupted = new AtomicBoolean(false);
    private final AtomicReference<Disposable> subscription = new AtomicReference<>();
    private final AnnotationQueueRoutingConfig config;
    private final FeatureFlags featureFlags;
    private final AnnotationQueueRoutingBufferService bufferService;
    private final LockService lockService;

    @Inject
    public AnnotationQueueRoutingFlushJob(
            @NonNull @Config("annotationQueueRouting") AnnotationQueueRoutingConfig config,
            @NonNull FeatureFlags featureFlags,
            @NonNull AnnotationQueueRoutingBufferService bufferService,
            @NonNull LockService lockService) {
        this.config = config;
        this.featureFlags = featureFlags;
        this.bufferService = bufferService;
        this.lockService = lockService;
    }

    @Override
    public void doJob(JobExecutionContext context) {
        if (!featureFlags.isAnnotationQueueAutomationEnabled() || interrupted.get()) {
            return;
        }

        Disposable disposable = lockService.bestEffortLock(
                FLUSH_LOCK,
                bufferService.flush()
                        .doOnNext(published -> {
                            if (published > 0) {
                                log.info("Annotation queue routing flush published messages, count '{}'", published);
                            }
                        })
                        .then(),
                Mono.fromRunnable(
                        () -> log.debug("Another instance is flushing the annotation queue routing buffer, skipping")),
                config.getJobLockTime().toJavaDuration(),
                config.getJobLockWaitTime().toJavaDuration(),
                true)
                .subscribe(
                        __ -> {
                        },
                        error -> log.error("Annotation queue routing flush job failed", error));
        subscription.set(disposable);
    }

    @Override
    public void interrupt() throws UnableToInterruptJobException {
        interrupted.set(true);
        Optional.ofNullable(subscription.getAndSet(null)).ifPresent(Disposable::dispose);
        log.info("AnnotationQueueRoutingFlushJob interrupted, cancelling any in-flight flush");
    }
}

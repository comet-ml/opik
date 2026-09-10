package com.comet.opik.api.resources.v1.jobs;

import com.comet.opik.domain.AnnotationQueueRoutingBufferService;
import com.comet.opik.infrastructure.AnnotationQueueRoutingConfig;
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
 * Schedules the periodic drain of the Redis-buffered routing work onto the routing stream (OPIK-6303).
 *
 * <p>Holds no business logic: it takes the cluster-wide lock (so one instance flushes per cycle) and
 * handles interruption. All the flush logic — Redis access, grouping and publishing — lives in
 * {@link AnnotationQueueRoutingBufferService#flush()}.
 */
@Slf4j
@Singleton
@DisallowConcurrentExecution
public class AnnotationQueueRoutingFlushJob extends Job implements InterruptableJob {

    private static final Lock FLUSH_LOCK = new Lock("annotation_queue_routing_flush_job:scan_lock");

    private final AtomicBoolean interrupted = new AtomicBoolean(false);

    private final AnnotationQueueRoutingConfig config;
    private final AnnotationQueueRoutingBufferService bufferService;
    private final LockService lockService;

    @Inject
    public AnnotationQueueRoutingFlushJob(
            @NonNull @Config("annotationQueueRouting") AnnotationQueueRoutingConfig config,
            @NonNull AnnotationQueueRoutingBufferService bufferService,
            @NonNull LockService lockService) {
        this.config = config;
        this.bufferService = bufferService;
        this.lockService = lockService;
    }

    @Override
    public void doJob(JobExecutionContext context) {
        if (!config.isEnabled()) {
            log.debug("Annotation queue routing is disabled, skipping flush");
            return;
        }

        if (interrupted.get()) {
            log.info("Annotation queue routing flush interrupted before execution, skipping");
            return;
        }

        lockService.bestEffortLock(
                FLUSH_LOCK,
                Mono.defer(bufferService::flush),
                Mono.defer(() -> {
                    log.debug("Could not acquire the routing flush lock, another instance is flushing");
                    return Mono.empty();
                }),
                config.getJobLockTime().toJavaDuration(),
                config.getJobLockWaitTime().toJavaDuration())
                .subscribe(
                        __ -> {
                        },
                        error -> log.error("Annotation queue routing flush failed", error));
    }

    @Override
    public void interrupt() throws UnableToInterruptJobException {
        interrupted.set(true);
        log.info("Annotation queue routing flush job interrupt requested");
    }
}

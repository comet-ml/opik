package com.comet.opik.api.resources.v1.jobs;

import com.comet.opik.domain.topics.TopicDataCleanupService;
import com.comet.opik.domain.topics.TopicWorkspaceService;
import com.comet.opik.infrastructure.TopicsConfig;
import com.comet.opik.infrastructure.lock.LockService;
import io.dropwizard.jobs.Job;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.InterruptableJob;
import org.quartz.JobExecutionContext;
import org.quartz.UnableToInterruptJobException;
import reactor.core.Disposable;
import reactor.core.Disposables;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.time.Duration;

import static com.comet.opik.infrastructure.lock.LockService.Lock;

/**
 * Deletes the topics of deleted projects that the project delete left, because its listener failed or a run saved
 * after it. Every pod fires it every {@code topics.storage.sweepInterval}, each at its own second (Quartz starts the
 * trigger at boot). The lock keeps two sweeps from overlapping; it is released when the sweep ends, fails or is cut
 * by {@code topics.storage.sweepTimeout}, so the next fire on any pod sweeps again. A pod shutdown interrupts the job
 * and cancels the sweep in flight.
 */
@Slf4j
@Singleton
@DisallowConcurrentExecution
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class TopicsSweepJob extends Job implements InterruptableJob {

    static final Lock SWEEP_LOCK = new Lock("topics:sweep");

    private final @NonNull @Config("topics") TopicsConfig config;
    private final @NonNull TopicWorkspaceService workspaceService;
    private final @NonNull TopicDataCleanupService cleanupService;
    private final @NonNull LockService lockService;
    private final Disposable.Composite inFlight = Disposables.composite();

    @Override
    public void doJob(JobExecutionContext context) {
        if (!config.isEnabled() || inFlight.isDisposed()) {
            return;
        }
        // Added before it is subscribed: an interrupt that comes first disposes it, and the update cancels it.
        var sweep = Disposables.swap();
        if (!inFlight.add(sweep)) {
            return;
        }
        var timeout = config.getStorage().getSweepTimeout().toJavaDuration();
        sweep.update(lockService.bestEffortLock(
                SWEEP_LOCK,
                sweep(timeout),
                Mono.fromRunnable(() -> log.debug("Topics sweep skipped, another instance holds the lock, lock '{}'",
                        SWEEP_LOCK.key())),
                timeout,
                Duration.ZERO)
                .doFinally(signal -> inFlight.remove(sweep))
                .subscribe(
                        topics -> {
                            if (topics > 0) {
                                log.info("Deleted the topics of deleted projects, topics '{}'", topics);
                            }
                        },
                        error -> log.error("Topics sweep failed", error)));
    }

    @Override
    public void interrupt() throws UnableToInterruptJobException {
        inFlight.dispose();
        log.info("TopicsSweepJob interrupted, cancelling any in-flight sweep");
    }

    /**
     * A failure or a timeout is logged here, so it never reaches the lock service, which would take a Redis error for
     * a lock held by another pod.
     */
    private Mono<Integer> sweep(Duration timeout) {
        return Mono.fromCallable(workspaceService::enabledWorkspaceIds)
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(cleanupService::deleteTopicsOfMissingProjects)
                .timeout(timeout)
                .onErrorResume(error -> {
                    log.error("Topics delete of deleted projects' topics failed", error);
                    return Mono.empty();
                });
    }
}

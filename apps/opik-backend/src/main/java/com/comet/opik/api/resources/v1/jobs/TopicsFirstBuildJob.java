package com.comet.opik.api.resources.v1.jobs;

import com.comet.opik.domain.topics.TopicsFirstBuildService;
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
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static com.comet.opik.infrastructure.lock.LockService.Lock;

/**
 * The topics first-build job: builds the list of each project that has none yet. Every pod fires it every
 * {@code topics.firstBuild.interval}, each at its own second (Quartz starts the trigger at boot). Two locks make that
 * one run per interval across the cluster:
 * <ul>
 * <li>the leader lock ({@link TopicsFirstBuildService#LEADER_LOCK}), held for the run and released when it ends, so two
 * runs never overlap; its lease ({@link TopicsFirstBuildService#lease()}) only matters when the pod dies mid-run;</li>
 * <li>the run lock, taken by the leader before it runs and never released: it expires one interval (less a small
 * margin for trigger jitter) after the run started, so a pod that fires sooner skips.</li>
 * </ul>
 * The run itself is {@link TopicsFirstBuildService#run(Instant)}. A shutdown interrupts the job: it cancels the run and
 * releases the leader lock, and the job starts no new run.
 */
@Slf4j
@Singleton
@DisallowConcurrentExecution
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class TopicsFirstBuildJob extends Job implements InterruptableJob {

    static final Lock RUN_LOCK = new Lock("topics:first_build:run");
    private static final Duration MAX_RUN_JITTER = Duration.ofSeconds(1);

    private final @NonNull @Config("topics") TopicsConfig config;
    private final @NonNull TopicsFirstBuildService firstBuildService;
    private final @NonNull LockService lockService;
    private final AtomicBoolean interrupted = new AtomicBoolean(false);
    private final AtomicReference<Disposable> subscription = new AtomicReference<>();

    @Override
    public void doJob(JobExecutionContext context) {
        if (!config.isEnabled() || !config.getFirstBuild().isEnabled() || interrupted.get()) {
            return;
        }
        var start = Instant.now();
        Disposable disposable = lockService.bestEffortLock(
                TopicsFirstBuildService.LEADER_LOCK,
                Mono.fromRunnable(() -> runIfDue(start)).subscribeOn(Schedulers.boundedElastic()),
                Mono.fromRunnable(() -> log.debug("Topics first builds skipped, lock '{}' is held by another instance",
                        TopicsFirstBuildService.LEADER_LOCK.key())),
                firstBuildService.lease(),
                Duration.ZERO)
                .subscribe(
                        __ -> {
                        },
                        error -> log.error("Topics first builds failed", error));
        subscription.set(disposable);
        // An interrupt between subscribe() and set() found nothing to dispose: dispose here. Whichever of the two
        // threads takes the subscription out disposes it, so it is disposed exactly once.
        if (interrupted.get()) {
            Optional.ofNullable(subscription.getAndSet(null)).ifPresent(Disposable::dispose);
        }
    }

    @Override
    public void interrupt() throws UnableToInterruptJobException {
        interrupted.set(true);
        Optional.ofNullable(subscription.getAndSet(null)).ifPresent(Disposable::dispose);
        log.info("TopicsFirstBuildJob interrupted, cancelling any in-flight run");
    }

    /**
     * Runs the first builds unless a run started, on any pod, less than one interval ago. A failure is logged here, so
     * it never reaches the lock service, which would take a Redis error for a lock held by another pod.
     */
    void runIfDue(Instant start) {
        try {
            if (Boolean.TRUE.equals(lockService.lockUsingToken(RUN_LOCK, runSlot()).block())) {
                firstBuildService.run(start);
            } else {
                log.debug("Topics first builds skipped, lock '{}' is held: a run started less than one interval ago",
                        RUN_LOCK.key());
            }
        } catch (RuntimeException exception) {
            log.error("Topics first builds failed", exception);
        }
    }

    /** One interval, less a margin for trigger jitter, so the pod whose trigger comes next is never one run late. */
    Duration runSlot() {
        var interval = config.getFirstBuild().getInterval().toJavaDuration();
        var margin = interval.dividedBy(10);
        return interval.minus(margin.compareTo(MAX_RUN_JITTER) < 0 ? margin : MAX_RUN_JITTER);
    }
}

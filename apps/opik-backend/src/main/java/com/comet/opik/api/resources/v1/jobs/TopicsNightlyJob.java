package com.comet.opik.api.resources.v1.jobs;

import com.comet.opik.domain.topics.TopicsNightlyService;
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
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static com.comet.opik.infrastructure.lock.LockService.Lock;

/**
 * The topics nightly job, on {@code topics.nightly.schedule} (UTC). Every pod fires it. Two locks make that one night
 * per date across the cluster:
 * <ul>
 * <li>the night lock ({@link TopicsNightlyService#NIGHT_LOCK}), held for the night and released when it ends, so two
 * nights never overlap, a night started by hand included; its lease ({@link TopicsNightlyService#lease()}) only matters
 * when the pod dies mid-night;</li>
 * <li>the date's run lock, taken by the night lock's holder before it runs and never released: it expires after a day,
 * so a pod whose trigger fires later for the same date skips.</li>
 * </ul>
 * The night itself is {@link TopicsNightlyService#runNight(Instant)}. A shutdown interrupts the job: it cancels the
 * night and releases the night lock, so the night can be run again by hand, and the job starts no new night.
 */
@Slf4j
@Singleton
@DisallowConcurrentExecution
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class TopicsNightlyJob extends Job implements InterruptableJob {

    private static final Duration RUN_SLOT = Duration.ofDays(1);

    private final @NonNull @Config("topics") TopicsConfig config;
    private final @NonNull TopicsNightlyService nightlyService;
    private final @NonNull LockService lockService;
    private final AtomicBoolean interrupted = new AtomicBoolean(false);
    private final AtomicReference<Disposable> subscription = new AtomicReference<>();

    static Lock runLock(LocalDate night) {
        return new Lock("topics:nightly:run:" + night);
    }

    @Override
    public void doJob(JobExecutionContext context) {
        if (!config.isEnabled() || !config.getNightly().isEnabled() || interrupted.get()) {
            return;
        }
        var start = Instant.now();
        Disposable disposable = lockService.bestEffortLock(
                TopicsNightlyService.NIGHT_LOCK,
                Mono.fromRunnable(() -> runIfDue(start)).subscribeOn(Schedulers.boundedElastic()),
                Mono.fromRunnable(() -> log.info("Topics night skipped, lock '{}' is held by another instance",
                        TopicsNightlyService.NIGHT_LOCK.key())),
                nightlyService.lease(),
                Duration.ZERO)
                .subscribe(
                        __ -> {
                        },
                        error -> log.error("Topics night failed", error));
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
        log.info("TopicsNightlyJob interrupted, cancelling any in-flight night");
    }

    /**
     * Runs the night unless one already ran for the date, on any pod. A failure is logged here, so it never reaches the
     * lock service, which would take a Redis error for a lock held by another pod.
     */
    void runIfDue(Instant start) {
        var lock = runLock(LocalDate.ofInstant(start, ZoneOffset.UTC));
        try {
            if (Boolean.TRUE.equals(lockService.lockUsingToken(lock, RUN_SLOT).block())) {
                nightlyService.runNight(start);
            } else {
                log.info("Topics night skipped, lock '{}' is held: the night already ran", lock.key());
            }
        } catch (RuntimeException exception) {
            log.error("Topics night failed", exception);
        }
    }
}

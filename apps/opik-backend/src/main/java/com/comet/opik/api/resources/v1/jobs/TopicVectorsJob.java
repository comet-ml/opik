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
 * Schedules the topic vectors tick. Every pod fires it every {@code topics.vectors.tickInterval}, each at its own
 * second (Quartz starts the trigger at boot). Two locks make that one tick per interval across the cluster:
 * <ul>
 * <li>the leader lock, held for the tick and released when it ends, so two ticks never overlap; its lease
 * ({@link TopicVectorsTickService#leaderLease()}) only matters when the pod dies mid-tick;</li>
 * <li>the tick lock, taken by the leader before it ticks and never released: it expires one interval (less a small
 * margin for trigger jitter) after the tick started, so a pod that fires sooner skips.</li>
 * </ul>
 * Holding the leader lock until expiry instead would stretch the interval to the lease, which is longer than the
 * interval; a lease of one interval would let a long tick overlap the next. The tick itself is
 * {@link TopicVectorsTickService#tick(Instant)}.
 */
@Slf4j
@Singleton
@DisallowConcurrentExecution
public class TopicVectorsJob extends Job implements InterruptableJob {

    static final Lock LEADER_LOCK = new Lock("topics:vectors:leader");
    static final Lock TICK_LOCK = new Lock("topics:vectors:tick");
    private static final Duration MAX_TICK_JITTER = Duration.ofSeconds(1);

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
                Mono.fromRunnable(() -> tickIfDue(start)),
                Mono.fromRunnable(() -> log.info("Topic vectors tick skipped, lock '{}' is held by another instance",
                        LEADER_LOCK.key())),
                tickService.leaderLease(),
                Duration.ZERO)
                .subscribe(
                        __ -> {
                        },
                        error -> log.error("Topic vectors tick failed", error));
        subscription.set(disposable);
        // An interrupt between subscribe() and set() found nothing to dispose: dispose here. Whichever of the two
        // threads takes the subscription out disposes it, so it is disposed exactly once.
        if (interrupted.get()) {
            Optional.ofNullable(subscription.getAndSet(null)).ifPresent(Disposable::dispose);
        }
    }

    /** Runs the tick unless one started, on any pod, less than one interval ago. */
    void tickIfDue(Instant start) {
        if (Boolean.TRUE.equals(lockService.lockUsingToken(TICK_LOCK, tickSlot()).block())) {
            tickService.tick(start);
        } else {
            log.info("Topic vectors tick skipped, lock '{}' is held: a tick ran less than one interval ago",
                    TICK_LOCK.key());
        }
    }

    /** One interval, less a margin for trigger jitter, so the pod whose trigger comes next is never one tick late. */
    Duration tickSlot() {
        var interval = config.getVectors().getTickInterval().toJavaDuration();
        var margin = interval.dividedBy(10);
        return interval.minus(margin.compareTo(MAX_TICK_JITTER) < 0 ? margin : MAX_TICK_JITTER);
    }

    @Override
    public void interrupt() throws UnableToInterruptJobException {
        interrupted.set(true);
        Optional.ofNullable(subscription.getAndSet(null)).ifPresent(Disposable::dispose);
        log.info("TopicVectorsJob interrupted, cancelling any in-flight tick");
    }
}

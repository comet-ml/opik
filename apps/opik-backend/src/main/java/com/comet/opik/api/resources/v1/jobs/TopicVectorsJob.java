package com.comet.opik.api.resources.v1.jobs;

import com.comet.opik.domain.topics.TopicVectorsTickService;
import com.comet.opik.infrastructure.TopicsConfig;
import com.comet.opik.infrastructure.lock.LockService;
import io.dropwizard.jobs.Job;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
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
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static com.comet.opik.infrastructure.lock.LockService.Lock;
import static io.opentelemetry.api.common.AttributeKey.stringKey;

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
 *
 * <p>Every firing is counted in {@code topics.vectors.ticks} by outcome: {@value #RAN}, {@value #NOT_DUE} (a tick ran
 * less than one interval ago), {@value #LOCK_HELD} (another pod holds the leader lock, or Redis failed: the lock
 * service then runs the same fallback) or {@value #FAILED}. Across the cluster, about one {@value #RAN} per interval.
 */
@Slf4j
@Singleton
@DisallowConcurrentExecution
public class TopicVectorsJob extends Job implements InterruptableJob {

    static final Lock LEADER_LOCK = new Lock("topics:vectors:leader");
    static final Lock TICK_LOCK = new Lock("topics:vectors:tick");
    private static final Duration MAX_TICK_JITTER = Duration.ofSeconds(1);

    static final AttributeKey<String> OUTCOME = stringKey("outcome");
    static final String RAN = "ran";
    static final String NOT_DUE = "not_due";
    static final String LOCK_HELD = "lock_held";
    static final String FAILED = "failed";
    static final List<String> OUTCOMES = List.of(RAN, NOT_DUE, LOCK_HELD, FAILED);

    private final AtomicBoolean interrupted = new AtomicBoolean(false);
    private final AtomicReference<Disposable> subscription = new AtomicReference<>();
    private final TopicsConfig config;
    private final TopicVectorsTickService tickService;
    private final LockService lockService;
    private final LongCounter ticks;

    @Inject
    public TopicVectorsJob(@NonNull @Config("topics") TopicsConfig config,
            @NonNull TopicVectorsTickService tickService, @NonNull LockService lockService) {
        this(config, tickService, lockService, GlobalOpenTelemetry.get().getMeter("opik.topics"));
    }

    TopicVectorsJob(@NonNull TopicsConfig config, @NonNull TopicVectorsTickService tickService,
            @NonNull LockService lockService, @NonNull Meter meter) {
        this.config = config;
        this.tickService = tickService;
        this.lockService = lockService;
        this.ticks = meter.counterBuilder("topics.vectors.ticks")
                .setDescription("Firings of the topic vectors job on this pod, by outcome: ran, not_due (a tick ran "
                        + "less than one interval ago), lock_held (another pod holds the leader lock, or Redis failed) "
                        + "or failed")
                .build();
        // The job is scheduled only when topics are enabled. Every outcome starts at 0 there, so increase() loses at
        // most a pod's first firing; a deployment with topics off publishes nothing.
        if (config.isEnabled()) {
            OUTCOMES.forEach(outcome -> ticks.add(0, Attributes.of(OUTCOME, outcome)));
        }
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
                Mono.fromRunnable(() -> {
                    count(LOCK_HELD);
                    log.debug("Topic vectors tick skipped, lock '{}' is held by another instance", LEADER_LOCK.key());
                }),
                tickService.leaderLease(),
                Duration.ZERO)
                .subscribe(
                        __ -> {
                        },
                        error -> {
                            count(FAILED);
                            log.error("Topic vectors tick failed", error);
                        });
        subscription.set(disposable);
        // An interrupt between subscribe() and set() found nothing to dispose: dispose here. Whichever of the two
        // threads takes the subscription out disposes it, so it is disposed exactly once.
        if (interrupted.get()) {
            Optional.ofNullable(subscription.getAndSet(null)).ifPresent(Disposable::dispose);
        }
    }

    /**
     * Runs the tick unless one started, on any pod, less than one interval ago. A failure is counted and logged here,
     * so it never reaches the lock service, which would take a Redis error for a lock held by another pod.
     */
    void tickIfDue(Instant start) {
        try {
            if (Boolean.TRUE.equals(lockService.lockUsingToken(TICK_LOCK, tickSlot()).block())) {
                tickService.tick(start);
                count(RAN);
            } else {
                count(NOT_DUE);
                log.debug("Topic vectors tick skipped, lock '{}' is held: a tick ran less than one interval ago",
                        TICK_LOCK.key());
            }
        } catch (RuntimeException exception) {
            count(FAILED);
            log.error("Topic vectors tick failed", exception);
        }
    }

    private void count(String outcome) {
        ticks.add(1, Attributes.of(OUTCOME, outcome));
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

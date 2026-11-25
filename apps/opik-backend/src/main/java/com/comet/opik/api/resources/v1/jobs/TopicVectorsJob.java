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
import reactor.core.Disposables;
import reactor.core.publisher.Mono;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static com.comet.opik.infrastructure.lock.LockService.Lock;
import static io.opentelemetry.api.common.AttributeKey.stringKey;

/**
 * Schedules the topic vectors tick. Every pod fires it every {@code topics.vectors.tickInterval}, each at its own
 * second (Quartz starts the trigger at boot). Two locks make that one tick per interval across the cluster:
 * <ul>
 * <li>the leader lock, held for the tick and released when it ends, so two ticks never overlap; its lease
 * ({@link #leaderLease()}) only matters when the pod dies mid-tick, because a tick that runs longer is cancelled;</li>
 * <li>the tick lock, taken by the leader before it ticks and never released: it expires one interval (less a small
 * margin for trigger jitter) after the tick started, so a pod that fires sooner skips.</li>
 * </ul>
 * Holding the leader lock until expiry instead would stretch the interval to the lease, which is longer than the
 * interval; a lease of one interval would let a long tick overlap the next. The tick itself is
 * {@link TopicVectorsTickService#tick(Instant)}. A pod shutdown interrupts the job (the lifecycle listener does it
 * before it stops the scheduler): it cancels every tick in flight, which frees the leader lock at once, and the job
 * starts no new tick.
 *
 * <p>Across the cluster, {@code topics.vectors.ticks} counts about one {@value #RAN} per interval.
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

    // Every tick in flight, not only the last one: a tick can outlast the interval, and the next fire must not detach it.
    private final Disposable.Composite inFlight = Disposables.composite();
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
        if (!config.isEnabled() || inFlight.isDisposed()) {
            return;
        }
        // The tick is added before it is subscribed: an interrupt that comes first disposes it, and the update below
        // then cancels the subscription.
        var tick = Disposables.swap();
        if (!inFlight.add(tick)) {
            return;
        }
        var start = Instant.now();
        tick.update(lockService.bestEffortLock(
                LEADER_LOCK,
                tickIfDue(start),
                Mono.fromRunnable(() -> {
                    count(LOCK_HELD);
                    log.debug("Topic vectors tick skipped, another instance holds the lock, lock '{}'",
                            LEADER_LOCK.key());
                }),
                leaderLease(),
                Duration.ZERO)
                // Started before the lease is set, so it fires first: a hung insert, which has no deadline of its
                // own, never holds the tick past the lease, and the cancel frees the lock.
                .timeout(leaderLease())
                .doFinally(signal -> inFlight.remove(tick))
                .subscribe(
                        __ -> {
                        },
                        error -> {
                            count(FAILED);
                            log.error("Topic vectors tick failed", error);
                        }));
    }

    /**
     * Runs the tick unless one started, on any pod, less than one interval ago. A failure is counted and logged here,
     * so it never reaches the lock service, which would take a Redis error for a lock held by another pod.
     */
    Mono<Void> tickIfDue(Instant start) {
        return Mono.defer(() -> lockService.lockUsingToken(TICK_LOCK, tickSlot()))
                .flatMap(due -> due
                        ? tickService.tick(start).doOnSuccess(__ -> count(RAN)).then()
                        : Mono.<Void>fromRunnable(() -> {
                            count(NOT_DUE);
                            log.debug("Topic vectors tick skipped, a tick ran less than one interval ago, lock '{}'",
                                    TICK_LOCK.key());
                        }))
                .onErrorResume(exception -> {
                    count(FAILED);
                    log.error("Topic vectors tick failed", exception);
                    return Mono.empty();
                });
    }

    private void count(String outcome) {
        ticks.add(1, Attributes.of(OUTCOME, outcome));
    }

    /**
     * How long the leader lock lasts, and the tick with it: the tick budget, plus what can run past its end: an embed
     * call started with {@code embedTimeout} left, a window read started just before it, and {@code leaseSlack} for
     * the insert after the last call. The same shape as {@code AiLabelsTickService.leaderLease()}.
     */
    Duration leaderLease() {
        var vectors = config.getVectors();
        return vectors.getTickBudget().toJavaDuration()
                .plus(vectors.getEmbedTimeout().toJavaDuration())
                .plus(vectors.getFind().getMaxExecutionTime().toJavaDuration())
                .plus(vectors.getLeaseSlack().toJavaDuration());
    }

    /** One interval, less a margin for trigger jitter, so the pod whose trigger comes next is never one tick late. */
    Duration tickSlot() {
        var interval = config.getVectors().getTickInterval().toJavaDuration();
        var margin = interval.dividedBy(10);
        return interval.minus(margin.compareTo(MAX_TICK_JITTER) < 0 ? margin : MAX_TICK_JITTER);
    }

    @Override
    public void interrupt() throws UnableToInterruptJobException {
        inFlight.dispose();
        log.info("TopicVectorsJob interrupted, cancelling any in-flight tick");
    }
}

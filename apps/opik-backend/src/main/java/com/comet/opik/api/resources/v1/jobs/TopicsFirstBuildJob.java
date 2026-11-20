package com.comet.opik.api.resources.v1.jobs;

import com.comet.opik.domain.topics.TopicDAO;
import com.comet.opik.domain.topics.TopicWorkspaceService;
import com.comet.opik.domain.topics.TopicsRunPlanner;
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
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;

import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;
import static com.comet.opik.infrastructure.lock.LockService.Lock;

/**
 * The topics first-build job: the leader plans the Topics runs ({@link TopicsRunPlanner#plan(Instant)}) and, once a
 * day, deletes the topics of deleted projects that the project delete missed. Every pod fires it every
 * {@code topics.firstBuild.planInterval}, each at its own second (Quartz starts the trigger at boot). The same locks as
 * {@link TopicVectorsJob} make that one plan per interval across the cluster:
 * <ul>
 * <li>the leader lock, held for the plan and released when it ends, so two plans never overlap; its lease
 * ({@link #leaderLease()}) only matters when the pod dies mid-plan, because a plan that runs longer is cancelled;</li>
 * <li>the plan lock, taken by the leader before it plans and never released: it expires one interval (less a small
 * margin for trigger jitter) after the plan started, so a pod that fires sooner skips. The sweep lock does the same
 * for {@code topics.firstBuild.sweepInterval}.</li>
 * </ul>
 * The runs themselves run on any pod, from the stream. A pod shutdown interrupts the job (the lifecycle listener does
 * it before it stops the scheduler): it cancels the plan in flight, which frees the leader lock at once, and the job
 * starts no new plan.
 */
@Slf4j
@Singleton
@DisallowConcurrentExecution
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class TopicsFirstBuildJob extends Job implements InterruptableJob {

    static final Lock LEADER_LOCK = new Lock("topics:first_build:leader");
    static final Lock PLAN_LOCK = new Lock("topics:first_build:plan");
    static final Lock SWEEP_LOCK = new Lock("topics:first_build:sweep");
    private static final Duration MAX_JITTER = Duration.ofSeconds(1);
    /** The plan's MySQL writes and stream publishes after its candidates, and the sweep. */
    private static final Duration LEASE_SLACK = Duration.ofMinutes(1);

    private final @NonNull @Config("topics") TopicsConfig config;
    private final @NonNull TopicsRunPlanner planner;
    private final @NonNull TopicWorkspaceService workspaceService;
    private final @NonNull TransactionTemplate template;
    private final @NonNull LockService lockService;
    // Every plan in flight, not only the last one: an interrupt must cancel the one the next fire did not start.
    private final Disposable.Composite inFlight = Disposables.composite();

    @Override
    public void doJob(JobExecutionContext context) {
        if (!config.isEnabled() || !config.getFirstBuild().isEnabled() || inFlight.isDisposed()) {
            return;
        }
        // The plan is added before it is subscribed: an interrupt that comes first disposes it, and the update below
        // then cancels the subscription.
        var plan = Disposables.swap();
        if (!inFlight.add(plan)) {
            return;
        }
        var start = Instant.now();
        plan.update(lockService.bestEffortLock(
                LEADER_LOCK,
                planIfDue(start).then(sweepIfDue()),
                Mono.fromRunnable(() -> log.debug("Topics plan skipped, another instance holds the lock, lock '{}'",
                        LEADER_LOCK.key())),
                leaderLease(),
                Duration.ZERO)
                .timeout(leaderLease())
                .doFinally(signal -> inFlight.remove(plan))
                .subscribe(
                        __ -> {
                        },
                        error -> log.error("Topics plan failed", error)));
    }

    @Override
    public void interrupt() throws UnableToInterruptJobException {
        inFlight.dispose();
        log.info("TopicsFirstBuildJob interrupted, cancelling any in-flight plan");
    }

    /**
     * Plans unless a plan started, on any pod, less than one interval ago. A failure is logged here, so it never
     * reaches the lock service, which would take a Redis error for a lock held by another pod, and the sweep still
     * runs.
     */
    Mono<Void> planIfDue(Instant start) {
        return Mono.defer(() -> lockService.lockUsingToken(PLAN_LOCK, slot(config.getFirstBuild().getPlanInterval())))
                .flatMap(due -> due
                        ? planner.plan(start).then()
                        : Mono.<Void>fromRunnable(() -> log.debug(
                                "Topics plan skipped, a plan ran less than one interval ago, lock '{}'",
                                PLAN_LOCK.key())))
                .onErrorResume(error -> {
                    log.error("Topics plan failed", error);
                    return Mono.empty();
                });
    }

    /** Deletes the topics of deleted projects unless a sweep started, on any pod, less than one interval ago. */
    Mono<Void> sweepIfDue() {
        return Mono.defer(() -> lockService.lockUsingToken(SWEEP_LOCK, slot(config.getFirstBuild().getSweepInterval())))
                .flatMap(due -> due ? sweep() : Mono.<Void>empty())
                .onErrorResume(error -> {
                    log.error("Topics delete of deleted projects' topics failed", error);
                    return Mono.empty();
                });
    }

    private Mono<Void> sweep() {
        return Mono.fromCallable(() -> {
            var workspaceIds = workspaceService.enabledWorkspaceIds();
            if (workspaceIds.isEmpty()) {
                return 0;
            }
            return template.inTransaction(WRITE,
                    handle -> handle.attach(TopicDAO.class).deleteTopicsOfMissingProjects(workspaceIds));
        })
                .subscribeOn(Schedulers.boundedElastic())
                .doOnNext(topics -> {
                    if (topics > 0) {
                        log.info("Deleted the topics of deleted projects, topics '{}'", topics);
                    }
                })
                .then();
    }

    /**
     * How long the leader lock lasts, and the plan with it: the planner stops counting candidates after
     * {@code topics.runs.planBudget}, a count started just before that ends within {@code topics.build.countTimeout},
     * and {@link #LEASE_SLACK} covers the rest.
     */
    Duration leaderLease() {
        return config.getRuns().getPlanBudget().toJavaDuration()
                .plus(config.getBuild().getCountTimeout().toJavaDuration())
                .plus(LEASE_SLACK);
    }

    /** One interval, less a margin for trigger jitter, so the pod whose trigger comes next is never one plan late. */
    static Duration slot(io.dropwizard.util.Duration interval) {
        var length = interval.toJavaDuration();
        var margin = length.dividedBy(10);
        return length.minus(margin.compareTo(MAX_JITTER) < 0 ? margin : MAX_JITTER);
    }
}

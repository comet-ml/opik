package com.comet.opik.domain.topics;

import com.comet.opik.api.error.ConflictException;
import com.comet.opik.domain.topics.TopicProject.RunMode;
import com.comet.opik.infrastructure.TopicsConfig;
import com.comet.opik.infrastructure.TopicsStage;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.lock.LockService;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.ServiceUnavailableException;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Supplier;

import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;

/**
 * Plans the Topics runs of the workspaces listed at stage {@code build} or later. One plan, on the leader only:
 * settle the runs in flight ({@link TopicsRunReconciler}), then fill the free slots of {@code topics.runs.maxInFlight},
 * across the cluster: first with the night's nightly updates while the night's budget lasts ({@link TopicsNightPass},
 * for the workspaces at stage {@code nightly} or later), then with labelling passes that did not finish and discovery
 * runs ({@link TopicsDiscoveryCandidates}), one of each in turn. Each run is written
 * {@code queued} on its project's row first, then published on the stream, where any pod claims it.
 */
@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class TopicsRunPlanner {

    /** Held by the plan in flight, on any pod: two plans never overlap. */
    public static final LockService.Lock LEADER_LOCK = new LockService.Lock("topics:discovery:leader");

    private final @NonNull @Config("topics") TopicsConfig config;
    private final @NonNull TransactionTemplate template;
    private final @NonNull TopicWorkspaceService workspaceService;
    private final @NonNull TopicRunStore store;
    private final @NonNull TopicsRunReconciler reconciler;
    private final @NonNull TopicsDiscoveryCandidates candidates;
    private final @NonNull TopicsRunPublisher publisher;
    private final @NonNull TopicsBackfillGate backfillGate;
    private final @NonNull TopicsNightPass nightPass;
    private final @NonNull LockService lockService;

    /**
     * How long the leader lock lasts, and the plan with it: the plan's one deadline, {@code topics.runs.planBudget},
     * cuts its counts and stops its queueing, {@code topics.run.countTimeout} covers the reconcile's counts before it,
     * and {@code topics.runs.leaseSlack} the queue write and publish in flight at it.
     */
    public static Duration leaderLease(@NonNull TopicsConfig config) {
        return config.getRuns().getPlanBudget().toJavaDuration()
                .plus(config.getRun().getCountTimeout().toJavaDuration())
                .plus(config.getRuns().getLeaseSlack().toJavaDuration());
    }

    /**
     * Plans now, under the leader lock, once a run ended and freed its slot, so the slot does not stay free until the
     * next interval. Best effort: nothing when another plan holds the lock, and a failure is logged. It does not take
     * the interval's plan lock, so the interval's plans go on as before.
     */
    public Mono<Void> planAfterRun() {
        if (!config.getRuns().isPlanAfterRun()) {
            return Mono.empty();
        }
        var lease = leaderLease(config);
        return lockService.bestEffortLock(LEADER_LOCK,
                Mono.defer(() -> plan(Instant.now())).then(),
                Mono.fromRunnable(() -> log.debug("Topics plan after a run skipped, another plan holds the lock")),
                lease, Duration.ZERO)
                .timeout(lease)
                .onErrorResume(error -> {
                    log.warn("Topics plan after a run failed; the next interval plans", error);
                    return Mono.empty();
                });
    }

    /**
     * One plan at {@code start}: the window ends at tomorrow's start (UTC), so it holds today's traces so far. The plan
     * has one deadline, {@code topics.runs.planBudget} from now, for all its phases.
     */
    public Mono<Integer> plan(@NonNull Instant start) {
        var night = LocalDate.ofInstant(start, ZoneOffset.UTC).plusDays(1);
        var deadline = PlanDeadline.after(config.getRuns().getPlanBudget().toJavaDuration());
        return Mono.fromCallable(() -> workspaceService.enabledWorkspaceIds(TopicsStage.BUILD))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(workspaceIds -> store.findInFlight()
                        .flatMap(runs -> reconciler.reconcile(runs, workspaceIds, night, start))
                        .flatMap(inFlight -> {
                            int free = config.getRuns().getMaxInFlight() - inFlight.size();
                            // The night pass runs with no free slot too: its skips and its end need none.
                            return nightPass.plan(start, deadline, free, this::queueOne)
                                    .flatMap(nightly -> {
                                        int left = free - nightly;
                                        if (left <= 0 || workspaceIds.isEmpty()) {
                                            return Mono.just(nightly);
                                        }
                                        return labelsDue(workspaceIds, start)
                                                .zipWith(candidates.find(workspaceIds, night, start, deadline))
                                                .flatMap(due -> queue(inTurn(due.getT1(), due.getT2(), night),
                                                        left, deadline))
                                                .map(queued -> nightly + queued);
                                    });
                        }))
                .doOnNext(queued -> log.info("Topics plan done, window until '{}', runs queued '{}'", night, queued));
    }

    /**
     * Queues the project's discovery run now, for the window that ends at {@code night}'s start, and returns its run id.
     * {@code ignoreBackfill} is the operator's way to build a project whose backfill cannot drain. The retry wait and
     * {@code maxInFlight} do not apply.
     *
     * @throws ConflictException when the workspace is not opted in at stage {@code build} or later, the window has too
     *                           few vectors, the project has a list or a run in flight or no trace in the window, or
     *                           its backfill gate is closed and {@code ignoreBackfill} is false
     */
    public Mono<UUID> runByHand(@NonNull String workspaceId, @NonNull UUID projectId, @NonNull LocalDate night,
            boolean ignoreBackfill) {
        var now = Instant.now();
        return optedIn(workspaceId, TopicsStage.BUILD, () -> candidates.findListless(workspaceId, projectId, night))
                .switchIfEmpty(Mono.error(() -> new ConflictException(("Project '%s' has a topic list, a run in "
                        + "flight, or no trace in the window until '%s'").formatted(projectId, night))))
                .flatMap(project -> {
                    var gate = backfillGate.discoveryGate(project, now);
                    if (!ignoreBackfill && !gate.isOpen()) {
                        return Mono.error(new ConflictException(("Project '%s' waits for its vectors backfill to "
                                + "drain, gate '%s'; ignore_backfill=true builds it anyway").formatted(projectId,
                                        gate.name().toLowerCase(Locale.ROOT))));
                    }
                    return candidates.windowVectors(workspaceId, projectId, night);
                })
                .flatMap(vectors -> {
                    if (vectors < config.getRun().getMinVectors()) {
                        return Mono.error(new ConflictException(
                                "Project '%s' has '%d' vectors in the window until '%s', the minimum is '%d'"
                                        .formatted(projectId, vectors, night, config.getRun().getMinVectors())));
                    }
                    return queueByHand(PlannedRun.builder().workspaceId(workspaceId).projectId(projectId)
                            .mode(RunMode.DISCOVERY).night(night).windowTraces(vectors).build())
                            .switchIfEmpty(Mono.error(() -> new ConflictException(
                                    "A topics run of project '%s' is in flight".formatted(projectId))));
                })
                .doOnNext(runId -> log.info("Topics run '{}' queued by hand, workspace '{}', project '{}', window "
                        + "until '{}'", runId, workspaceId, projectId, night));
    }

    /**
     * Queues the project's nightly update for {@code night} now, by hand, and returns its run id
     * ({@link TopicsNightPass#byHand}).
     *
     * @throws ConflictException           when the workspace is not opted in at stage {@code nightly} or later, the
     *                                     night would skip the project (the pause aside), or it has a run in flight
     * @throws ServiceUnavailableException when the run could not be published: it is dropped, and the caller tries
     *                                     again
     */
    public Mono<UUID> nightByHand(@NonNull String workspaceId, @NonNull UUID projectId, @NonNull LocalDate night) {
        return optedIn(workspaceId, TopicsStage.NIGHTLY,
                () -> nightPass.byHand(workspaceId, projectId, night, this::queueByHand));
    }

    /**
     * Forgets the project's saved list by hand, so its next discovery run, planned or by hand, builds a new one. The
     * workspace comes from the Reactor context.
     *
     * @throws ConflictException when the workspace is not opted in at stage {@code build} or later, or the project has
     *                           no topics row or a run in flight
     */
    public Mono<Void> resetByHand(@NonNull UUID projectId) {
        return Mono.deferContextual(ctx -> {
            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);
            return optedIn(workspaceId, TopicsStage.BUILD, () -> store.resetList(projectId))
                    .filter(Boolean::booleanValue)
                    .switchIfEmpty(Mono.error(() -> new ConflictException(
                            "Project '%s' has no topics row or a run in flight".formatted(projectId))))
                    .doOnNext(reset -> log.info("Topics list reset by hand, workspace '{}', project '{}'",
                            workspaceId, projectId))
                    .then();
        });
    }

    /**
     * Forgets the saved lists of the workspace's projects by hand, as {@link #resetByHand} does one project's. A project
     * with a run in flight keeps its list and is returned as skipped.
     *
     * @throws ConflictException when the workspace is not opted in at stage {@code build} or later
     */
    public Mono<WorkspaceReset> resetWorkspaceByHand(@NonNull String workspaceId) {
        return optedIn(workspaceId, TopicsStage.BUILD, () -> store.resetLists(workspaceId))
                .doOnNext(reset -> log.info("Topics lists reset by hand, workspace '{}', reset '{}', skipped in "
                        + "flight '{}'", workspaceId, reset.reset().size(), reset.inFlight().size()));
    }

    /**
     * {@code then} once the workspace reaches {@code stage}, checked on a bounded-elastic thread (a config and MySQL
     * read); a {@link ConflictException} otherwise.
     */
    private <T> Mono<T> optedIn(String workspaceId, TopicsStage stage, Supplier<Mono<T>> then) {
        return Mono.fromCallable(() -> workspaceService.reaches(workspaceId, stage))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(enabled -> enabled ? then.get() : Mono.error(notOptedIn(workspaceId, stage)));
    }

    private static ConflictException notOptedIn(String workspaceId, TopicsStage stage) {
        return new ConflictException(("Workspace is not opted in to topics runs: it needs to be in "
                + "topics.enabledWorkspaces at the stage or later, and enabled in ai_label_workspaces, workspace "
                + "'%s', stage '%s'").formatted(workspaceId, stage.configName()));
    }

    /**
     * Queues the run and publishes it; empty when the project has a run in flight. A failed publish leaves the run
     * queued: the next plans publish it again.
     */
    Mono<UUID> queueOne(@NonNull PlannedRun run) {
        return queueAndPublish(run, (runId, error) -> {
            log.warn("Topics run queued but not published, a later plan publishes it, run '{}', project '{}'",
                    runId, run.projectId(), error);
            return Mono.just(runId);
        });
    }

    /**
     * Queues a run started by hand and publishes it. When the publish fails, the run is dropped and the caller gets an
     * error to retry on: a run left queued would block the project's next run by hand until a plan publishes it again.
     * A run that a pod already claimed was published after all, for example by a publish that timed out after the
     * stream took it, so the caller gets its id. The drop is one compare-and-set on "never claimed", and a requeue
     * keeps the claim, so a pod that stops meanwhile cannot turn a claimed run into a 503.
     */
    private Mono<UUID> queueByHand(PlannedRun run) {
        return queueAndPublish(run, (runId, error) -> {
            log.warn("Topics run by hand not published, dropping it, run '{}', project '{}'", runId,
                    run.projectId(), error);
            return store.dropUnclaimed(run.workspaceId(), run.projectId(), runId)
                    // Not dropped: a pod claimed the run, or a plan cancelled it.
                    .flatMap(dropped -> dropped
                            ? Mono.just(false)
                            : store.isClaimed(run.workspaceId(), run.projectId(), runId))
                    .onErrorResume(storeError -> {
                        log.error("Topics run by hand not published, and its row not dropped or not read, it may "
                                + "stay queued until a plan publishes it, run '{}', project '{}'", runId,
                                run.projectId(), storeError);
                        return Mono.just(false);
                    })
                    .flatMap(claimed -> {
                        if (!claimed) {
                            return Mono.error(new ServiceUnavailableException(
                                    "The topics run could not be started, try again, project '%s'"
                                            .formatted(run.projectId())));
                        }
                        log.info("Topics run by hand claimed after its publish error, so it was published, "
                                + "run '{}', project '{}'", runId, run.projectId());
                        return Mono.just(runId);
                    });
        });
    }

    /** Queues the run and publishes it; empty when the project has a run in flight. */
    private Mono<UUID> queueAndPublish(PlannedRun run, BiFunction<UUID, Throwable, Mono<UUID>> onPublishError) {
        return store.queue(run.workspaceId(), run.projectId(), run.mode())
                .flatMap(runId -> publisher.publish(run.message(runId))
                        .thenReturn(runId)
                        .onErrorResume(error -> onPublishError.apply(runId, error)));
    }

    private Mono<List<TopicProjectKey>> labelsDue(List<String> workspaceIds, Instant now) {
        var failedAfter = now.minus(config.getDiscovery().retryDelays().getFirst());
        return Mono.fromCallable(() -> template.inTransaction(READ_ONLY,
                handle -> handle.attach(TopicProjectDAO.class).findLabelsDue(workspaceIds, failedAfter)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * One labelling pass, then one discovery run, in turn. Each is taken only once the one before it is queued or
     * skipped, so a discovery run's workspace is counted only when its turn comes ({@link
     * TopicsDiscoveryCandidates.Due#next}).
     */
    private static Flux<PlannedRun> inTurn(List<TopicProjectKey> labels, TopicsDiscoveryCandidates.Due builds,
            LocalDate night) {
        var rest = labels.isEmpty() ? labels : labels.subList(1, labels.size());
        return Flux.just(true, false).concatMap(labelTurn -> labelTurn
                ? Flux.fromIterable(labels).take(1).map(TopicsRunPlanner::labels)
                : builds.next()
                        .flatMapMany(next -> Flux.just(discovery(next.candidate(), night))
                                .concatWith(Flux.defer(() -> inTurn(rest, next.rest(), night))))
                        .switchIfEmpty(Flux.defer(() -> Flux.fromIterable(rest).map(TopicsRunPlanner::labels))),
                0);
    }

    private static PlannedRun discovery(TopicsDiscoveryCandidates.Candidate build, LocalDate night) {
        return PlannedRun.builder().workspaceId(build.workspaceId()).projectId(build.projectId())
                .mode(RunMode.DISCOVERY).night(night).windowTraces(build.vectors()).build();
    }

    private static PlannedRun labels(TopicProjectKey project) {
        return PlannedRun.builder().workspaceId(project.workspaceId()).projectId(project.projectId())
                .mode(RunMode.LABELS).build();
    }

    /**
     * Queues the runs in order until {@code free} are queued or the deadline passes; a project that got a run meanwhile
     * is skipped. It takes the next run only once this one is queued or skipped.
     */
    private Mono<Integer> queue(Flux<PlannedRun> runs, int free, PlanDeadline deadline) {
        return runs
                .takeWhile(run -> !deadline.passed())
                .concatMap(run -> queueOne(run).onErrorResume(error -> {
                    log.warn("Topics run not queued, project '{}'", run.projectId(), error);
                    return Mono.empty();
                }), 0)
                .take(free)
                .count()
                .map(Long::intValue);
    }
}

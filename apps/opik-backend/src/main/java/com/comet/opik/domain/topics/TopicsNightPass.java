package com.comet.opik.domain.topics;

import com.comet.opik.api.error.ConflictException;
import com.comet.opik.domain.ProjectService;
import com.comet.opik.domain.WorkspaceNameService;
import com.comet.opik.domain.topics.TopicProject.RunMode;
import com.comet.opik.infrastructure.TopicsConfig;
import com.comet.opik.infrastructure.TopicsStage;
import com.comet.opik.infrastructure.lock.LockService;
import io.dropwizard.lifecycle.Managed;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.vyarus.dropwizard.guice.module.installer.feature.eager.EagerSingleton;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;

/**
 * The night pass of each plan ({@link TopicsRunPlanner#plan}): queues the nightly updates of the projects that have a
 * topic list, in the workspaces listed at stage {@code nightly} or later, as runs of mode {@code update} that share
 * {@code topics.runs.maxInFlight} with the discovery runs.
 *
 * <p>The night is the UTC date of the {@code topics.nightly.schedule} fire that starts it; it starts at its first fire
 * that day. From the night's start until {@code topics.nightly.budget} after it, each plan decides on the projects the
 * night has not looked at yet ({@code last_night} before the night) and that have no run in flight. A project is
 * skipped when it is deleted, its list already covers the night, or its last {@code topics.nightly.pauseFailures}
 * runs failed within {@code topics.nightly.pauseNights} nights ({@link NightTriage}). Then, while slots are free, the
 * rest are counted, one count per workspace, and decided in {@link NightTriage#queueOrder}: one under
 * {@code topics.run.minVectors} is skipped, one whose count fails is left for the next night, and the others are
 * queued with their count. A project with a run in flight waits for a later plan. No project is queued after the
 * budget: the first plan after it records every project the night did not look at as not reached.
 *
 * <p>Each decision is counted in {@code topics.nightly.decisions}, logged, and written to the project's row: one that
 * looked at it sets {@code last_night}, so later plans of the night leave it alone; a night that did not reach it adds
 * the night to {@code not_reached_nights}, once. The night's end is counted in {@code topics.nightly.nights} and logged
 * once, by the plan that takes the night's end key in Redis, with the counts read from the rows.
 *
 * <p>It keeps no state between plans. At startup it sets the decision and outcome series of the workspaces listed then
 * at 0.
 */
@Slf4j
@Singleton
@EagerSingleton
class TopicsNightPass implements Managed {

    private final TopicsConfig config;
    private final TransactionTemplate template;
    private final TopicWorkspaceService workspaceService;
    private final TopicsDiscoveryCandidates candidates;
    private final TopicsMetrics metrics;
    private final WorkspaceNameService workspaceNames;
    private final ProjectService projectService;
    private final LockService lockService;

    @Inject
    TopicsNightPass(@NonNull @Config("topics") TopicsConfig config, @NonNull TransactionTemplate template,
            @NonNull TopicWorkspaceService workspaceService, @NonNull TopicsDiscoveryCandidates candidates,
            @NonNull TopicsMetrics metrics, @NonNull WorkspaceNameService workspaceNames,
            @NonNull ProjectService projectService, @NonNull LockService lockService) {
        this.config = config;
        this.template = template;
        this.workspaceService = workspaceService;
        this.candidates = candidates;
        this.metrics = metrics;
        this.workspaceNames = workspaceNames;
        this.projectService = projectService;
        this.lockService = lockService;
    }

    /**
     * Sets every decision and outcome series at 0 for the workspaces listed at stage {@code nightly} or later now, so
     * each first decision is seen as a rise. A failed read is logged: the series then start at their first decision.
     */
    @Override
    public void start() {
        if (!config.isEnabled()) {
            return;
        }
        try {
            for (var outcome : NightOutcome.values()) {
                metrics.night(outcome, 0);
            }
            for (var workspaceId : workspaceService.enabledWorkspaceIds(TopicsStage.NIGHTLY)) {
                var workspace = workspace(workspaceId);
                for (var decision : NightDecision.values()) {
                    if (decision != NightDecision.SKIP) {
                        metrics.nightlyDecision(workspace, decision, null, 0);
                    }
                }
                for (var reason : NightSkipReason.values()) {
                    metrics.nightlyDecision(workspace, NightDecision.SKIP, reason, 0);
                }
            }
        } catch (RuntimeException exception) {
            log.warn("Topics nightly series could not be set at 0 at startup", exception);
        }
    }

    /**
     * One plan's night pass at {@code now}: queues up to {@code free} nightly updates through {@code queue} and
     * returns how many it queued. Nothing when no night is on or no workspace is listed at stage {@code nightly} or
     * later. A read that fails, for every workspace or for one, is logged and counted as a failed night once; the other
     * workspaces go on, and the next plan reads the failed ones again.
     */
    Mono<Integer> plan(@NonNull Instant now, int free, @NonNull Function<TopicsRunPlanner.Planned, Mono<UUID>> queue) {
        var nightly = config.getNightly();
        var found = TopicsNight.current(nightly.getSchedule(), now);
        if (found.isEmpty()) {
            return Mono.just(0);
        }
        var night = found.get();
        var budgetEnd = night.start().plus(nightly.getBudget().toJavaDuration());
        var pass = jdbi(() -> workspaceService.enabledWorkspaceIds(TopicsStage.NIGHTLY))
                .flatMap(workspaceIds -> {
                    if (workspaceIds.isEmpty()) {
                        return Mono.just(0);
                    }
                    return now.isBefore(budgetEnd)
                            ? decide(night, workspaceIds, now, budgetEnd, free, queue)
                            : end(night, workspaceIds, now).thenReturn(0);
                });
        return pass.onErrorResume(error -> {
            log.error("Topics night '{}' pass failed; the next plan goes on with it", night.date(), error);
            metrics.night(NightOutcome.FAILED, 1);
            return Mono.just(0);
        });
    }

    /**
     * Queues the project's nightly update for {@code night} now and returns its run id. {@code topics.runs.maxInFlight}
     * and the night's budget do not apply, and the night fields are not written: it is one project's run, not a night.
     *
     * The caller checks that the workspace is opted in at stage {@code nightly} or later.
     *
     * @throws ConflictException when the project is gone or has no list, its list already covers the night, its window
     *                           has too few vectors, or it has a run in flight
     */
    Mono<UUID> byHand(@NonNull String workspaceId, @NonNull UUID projectId, @NonNull LocalDate night,
            @NonNull Function<TopicsRunPlanner.Planned, Mono<UUID>> queue) {
        return jdbi(() -> {
            // A deleted project keeps its rows until the sweep: run only a project that exists.
            var row = projectService.findByIds(workspaceId, Set.of(projectId)).isEmpty()
                    ? null
                    : template.inTransaction(READ_ONLY, handle -> handle.attach(TopicProjectDAO.class)
                            .findProjects(workspaceId, Set.of(projectId))).stream()
                            .filter(found -> found.listRunId() != null).findFirst().orElse(null);
            if (row == null) {
                throw new ConflictException(
                        "Project '%s' has no topic list: a discovery run makes one".formatted(projectId));
            }
            if (NightTriage.covers(row, TopicsNight.windowTo(night))) {
                throw new ConflictException(("Project '%s' has a list saved with its window until '%s', at or after "
                        + "the night '%s'").formatted(projectId, row.windowUntil(), night));
            }
            return row;
        })
                .flatMap(__ -> candidates.windowVectors(workspaceId, projectId, night))
                .flatMap(vectors -> {
                    int min = config.getRun().getMinVectors();
                    if (vectors < min) {
                        return Mono.error(new ConflictException(
                                "Project '%s' has '%d' vectors in the window until '%s', the minimum is '%d'"
                                        .formatted(projectId, vectors, night, min)));
                    }
                    return queue.apply(TopicsRunPlanner.Planned.builder().workspaceId(workspaceId)
                            .projectId(projectId).mode(RunMode.UPDATE).night(night).windowTraces(vectors).build())
                            .switchIfEmpty(Mono.error(() -> new ConflictException(
                                    "A topics run of project '%s' is in flight".formatted(projectId))));
                })
                .doOnNext(runId -> log.info("Topics run '{}' queued by hand, mode '{}', workspace '{}', project '{}', "
                        + "night '{}'", runId, RunMode.UPDATE.getValue(), workspaceId, projectId, night));
    }

    /** Within the budget: the cheap skips first, then the counts and queues while slots are free. */
    private Mono<Integer> decide(TopicsNight night, Collection<String> workspaceIds, Instant now, Instant budgetEnd,
            int free, Function<TopicsRunPlanner.Planned, Mono<UUID>> queue) {
        // The plan's clock starts before its read, so the read's time counts against the budget too.
        long began = System.nanoTime();
        return jdbi(() -> {
            var read = due(night, workspaceIds);
            read.failed().forEach((workspaceId, error) -> log.error("Topics night '{}' could not read workspace '{}'; "
                    + "the next plan reads it again", night.date(), workspaceId, error));
            if (!read.failed().isEmpty()) {
                metrics.night(NightOutcome.FAILED, 1);
            }
            read.due().skipped().forEach((reason, keys) -> record(night, keys, NightDecision.SKIP, reason));
            return read.due().candidates();
        })
                .flatMap(due -> {
                    if (free <= 0 || due.isEmpty()) {
                        return Mono.just(0);
                    }
                    // A plan stops counting and queueing after topics.runs.planBudget, and none goes past the budget.
                    var planEnd = now.plus(config.getRuns().getPlanBudget().toJavaDuration());
                    var stopAt = planEnd.isBefore(budgetEnd) ? planEnd : budgetEnd;
                    BooleanSupplier inTime = () -> now.plusNanos(System.nanoTime() - began).isBefore(stopAt);
                    var byWorkspace = due.stream().collect(Collectors.groupingBy(
                            NightTriage.NightCandidate::workspaceId, LinkedHashMap::new,
                            Collectors.mapping(NightTriage.NightCandidate::projectId, Collectors.toList())));
                    // One count per workspace; a workspace not counted in time waits for a later plan.
                    return Flux.fromIterable(byWorkspace.entrySet())
                            .takeWhile(workspace -> inTime.getAsBoolean())
                            .concatMap(workspace -> candidates
                                    .windowVectors(workspace.getKey(), workspace.getValue(), night.date())
                                    .map(counts -> Map.entry(workspace.getKey(), counts)))
                            .collectMap(Map.Entry::getKey, Map.Entry::getValue)
                            .flatMap(counts -> Flux.fromIterable(due)
                                    .filter(candidate -> counts.containsKey(candidate.workspaceId()))
                                    .concatMap(candidate -> decideOne(candidate,
                                            counts.get(candidate.workspaceId()).get(candidate.projectId()), night,
                                            inTime, queue))
                                    .filter(Boolean::booleanValue)
                                    .take(free)
                                    .count())
                            .map(Long::intValue);
                })
                .doOnNext(queued -> log.info("Topics night '{}' plan done, nightly updates queued '{}'", night.date(),
                        queued));
    }

    /** A plan's read of the night: the triage of the workspaces it read, and the error of each it could not. */
    private record NightRead(NightTriage.NightDue due, Map<String, RuntimeException> failed) {
    }

    /**
     * The projects of the workspaces with a saved list, not looked at tonight and with no run in flight, triaged
     * ({@link NightTriage}). A workspace whose projects cannot be checked is left out, with its error.
     */
    private NightRead due(TopicsNight night, Collection<String> workspaceIds) {
        var rows = template.inTransaction(READ_ONLY,
                handle -> handle.attach(TopicProjectDAO.class).findNightDue(workspaceIds, night.date(), night.start()));
        var existing = new HashSet<TopicProjectKey>();
        var failed = new HashMap<String, RuntimeException>();
        rows.stream().collect(Collectors.groupingBy(TopicProject::workspaceId,
                Collectors.mapping(TopicProject::projectId, Collectors.toSet())))
                .forEach((workspaceId, projectIds) -> {
                    try {
                        projectService.findByIds(workspaceId, projectIds)
                                .forEach(project -> existing.add(new TopicProjectKey(workspaceId, project.id())));
                    } catch (RuntimeException exception) {
                        failed.put(workspaceId, exception);
                    }
                });
        var read = rows.stream().filter(row -> !failed.containsKey(row.workspaceId())).toList();
        var nightly = config.getNightly();
        return new NightRead(
                NightTriage.triage(read, existing, night, nightly.getPauseFailures(), nightly.getPauseNights()),
                failed);
    }

    /**
     * Decides on the project with its window count, null when its count failed; true when it queued it. Past the plan's
     * time ({@code inTime} false) it queues nothing and records nothing: a later plan decides it, or the night's end
     * records it not reached.
     */
    private Mono<Boolean> decideOne(NightTriage.NightCandidate candidate, Long vectors, TopicsNight night,
            BooleanSupplier inTime, Function<TopicsRunPlanner.Planned, Mono<UUID>> queue) {
        var key = new TopicProjectKey(candidate.workspaceId(), candidate.projectId());
        if (vectors == null) {
            return jdbi(() -> {
                record(night, List.of(key), NightDecision.COUNT_FAILED, null);
                return false;
            });
        }
        if (vectors < config.getRun().getMinVectors()) {
            return jdbi(() -> {
                record(night, List.of(key), NightDecision.SKIP, NightSkipReason.SMALL);
                return false;
            });
        }
        if (!inTime.getAsBoolean()) {
            return Mono.just(false);
        }
        return queue.apply(TopicsRunPlanner.Planned.builder().workspaceId(candidate.workspaceId())
                .projectId(candidate.projectId()).mode(RunMode.UPDATE).night(night.date()).windowTraces(vectors)
                .build())
                .flatMap(runId -> jdbi(() -> {
                    log.info("Topics night '{}' queued run '{}', workspace '{}', project '{}', vectors '{}'",
                            night.date(), runId, candidate.workspaceId(), candidate.projectId(), vectors);
                    record(night, List.of(key), NightDecision.UPDATE, null);
                    return true;
                }))
                .onErrorResume(error -> {
                    log.warn("Topics night '{}' could not queue project '{}'; a later plan tries again", night.date(),
                            candidate.projectId(), error);
                    return Mono.just(false);
                })
                // It got a run meanwhile: a later plan decides it.
                .defaultIfEmpty(false);
    }

    /**
     * After the budget: records the projects with a list the night did not look at as not reached (once per night,
     * whichever plan writes it). Each such plan reads the night's counts; the one that takes the night's end key then
     * counts the night finished and logs it, once. A failed read or a Redis error leaves the key for the next plan.
     */
    private Mono<Void> end(TopicsNight night, Collection<String> workspaceIds, Instant now) {
        var endKey = new LockService.Lock("topics:nightly:end:" + night.date());
        return jdbi(() -> {
            var left = template.inTransaction(READ_ONLY, handle -> handle.attach(TopicProjectDAO.class)
                    .findNotReached(workspaceIds, night.date(), night.start()));
            if (!left.isEmpty()) {
                record(night, left, NightDecision.NOT_REACHED, null);
            }
            return night;
        })
                // Counted before the key: a failed count leaves the key free for the next plan.
                .then(jdbi(() -> template.inTransaction(READ_ONLY,
                        handle -> handle.attach(TopicProjectDAO.class).countNight(workspaceIds, night.date(),
                                night.start()))))
                // The key outlives every plan of the night: no later plan sees this night as current.
                .filterWhen(counts -> Mono
                        .defer(() -> lockService.lockUsingToken(endKey, Duration.between(now, night.currentUntil())))
                        .onErrorResume(error -> {
                            log.warn("Topics night '{}' could not take its end key '{}'; the next plan tries again",
                                    night.date(), endKey.key(), error);
                            return Mono.just(false);
                        }))
                .doOnNext(counts -> {
                    metrics.night(NightOutcome.FINISHED, 1);
                    log.info("Topics night '{}' ended, started '{}', projects looked at '{}', not reached '{}'",
                            night.date(), night.start(), counts.looked(), counts.notReached());
                })
                .then();
    }

    /**
     * Logs, writes and counts one decision on several projects: one log line and one write per workspace. A write that
     * fails is logged and not counted, and the pass goes on: a later plan of the night decides those projects again.
     */
    private void record(TopicsNight night, Collection<TopicProjectKey> keys, NightDecision decision,
            NightSkipReason reason) {
        keys.stream().collect(Collectors.groupingBy(TopicProjectKey::workspaceId,
                Collectors.mapping(TopicProjectKey::projectId, Collectors.toSet())))
                .forEach((workspaceId, projectIds) -> {
                    var reasonValue = reason == null ? null : reason.getValue();
                    log.info("Topics night '{}' decided, workspace '{}', projects '{}', decision '{}', reason '{}'",
                            night.date(), workspaceId, projectIds, decision.getValue(), reasonValue);
                    try {
                        template.inTransaction(WRITE, handle -> {
                            var dao = handle.attach(TopicProjectDAO.class);
                            return decision == NightDecision.NOT_REACHED
                                    ? dao.markNotReached(workspaceId, projectIds, night.date())
                                    : dao.markLooked(workspaceId, projectIds, night.date());
                        });
                        // Counted once written: a project whose write failed is decided again by a later plan.
                        metrics.nightlyDecision(workspace(workspaceId), decision, reason, projectIds.size());
                    } catch (RuntimeException exception) {
                        log.warn("Topics night '{}' could not write its decision '{}', workspace '{}'", night.date(),
                                decision.getValue(), workspaceId, exception);
                    }
                });
    }

    private TopicsMetrics.Workspace workspace(String workspaceId) {
        try {
            return new TopicsMetrics.Workspace(workspaceId, workspaceNames.nameOrId(workspaceId));
        } catch (RuntimeException exception) {
            return new TopicsMetrics.Workspace(workspaceId, workspaceId);
        }
    }

    private static <T> Mono<T> jdbi(Callable<T> call) {
        return Mono.fromCallable(call).subscribeOn(Schedulers.boundedElastic());
    }
}

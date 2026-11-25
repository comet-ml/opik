package com.comet.opik.domain.topics;

import com.comet.opik.api.Project;
import com.comet.opik.domain.ProjectService;
import com.comet.opik.domain.WorkspaceNameService;
import com.comet.opik.domain.topics.TopicProject.RunMode;
import com.comet.opik.infrastructure.TopicsConfig;
import com.comet.opik.infrastructure.TopicsStage;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.Builder;
import lombok.NonNull;
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
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
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
 * runs failed within {@code topics.nightly.pauseNights} nights. Then, while slots are free, the rest are counted one by
 * one in {@link #queueOrder}: one under {@code topics.run.minVectors} is skipped, one whose count fails is left for the
 * next night, and the others are queued with their count. A project with a run in flight waits for a later plan. No
 * project is queued after the budget: the first plan after it records every project the night did not look at as not
 * reached.
 *
 * <p>Each decision is counted in {@code topics.nightly.decisions}, logged, and written to the project's row: one that
 * looked at it sets {@code last_night}, so later plans of the night leave it alone; a night that did not reach it adds
 * the night to {@code not_reached_nights}, once. The night's end is counted in {@code topics.nightly.nights} and logged by the leader that saw it; a leader that took
 * over during the night logs only what it decided.
 */
@Slf4j
@Singleton
class TopicsNightPass {

    private final TopicsConfig config;
    private final TransactionTemplate template;
    private final TopicWorkspaceService workspaceService;
    private final TopicsDiscoveryCandidates candidates;
    private final TopicsMetrics metrics;
    private final WorkspaceNameService workspaceNames;
    private final ProjectService projectService;
    /** What this leader saw of the last night it planned: for the night's end, its metrics and its log line. */
    private final AtomicReference<Tally> tally = new AtomicReference<>();

    @Inject
    TopicsNightPass(@NonNull @Config("topics") TopicsConfig config, @NonNull TransactionTemplate template,
            @NonNull TopicWorkspaceService workspaceService, @NonNull TopicsDiscoveryCandidates candidates,
            @NonNull TopicsMetrics metrics, @NonNull WorkspaceNameService workspaceNames,
            @NonNull ProjectService projectService) {
        this.config = config;
        this.template = template;
        this.workspaceService = workspaceService;
        this.candidates = candidates;
        this.metrics = metrics;
        this.workspaceNames = workspaceNames;
        this.projectService = projectService;
    }

    /** A project the night counts and may queue. */
    @Builder(toBuilder = true)
    record NightCandidate(@NonNull String workspaceId, @NonNull UUID projectId, Instant windowUntil) {
    }

    /**
     * One plan's night pass at {@code now}: queues up to {@code free} nightly updates through {@code queue} and
     * returns how many it queued. Nothing when no night is on or no workspace is listed at stage {@code nightly} or
     * later. A read that fails is logged and counted as a failed night; the next plan goes on.
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
                    var current = tally(night);
                    return now.isBefore(budgetEnd)
                            ? decide(night, workspaceIds, now, budgetEnd, free, queue, current)
                            : end(night, workspaceIds, current).thenReturn(0);
                });
        return pass.onErrorResume(error -> {
            log.error("Topics night '{}' pass failed; the next plan goes on with it", night.date(), error);
            metrics.night(NightOutcome.FAILED, 1);
            return Mono.just(0);
        });
    }

    /** Within the budget: the cheap skips first, then the counts and queues while slots are free. */
    private Mono<Integer> decide(TopicsNight night, Collection<String> workspaceIds, Instant now, Instant budgetEnd,
            int free, Function<TopicsRunPlanner.Planned, Mono<UUID>> queue, Tally current) {
        return jdbi(() -> due(night, workspaceIds, current))
                .flatMap(due -> {
                    if (free <= 0 || due.isEmpty()) {
                        return Mono.just(0);
                    }
                    // A plan stops counting after topics.runs.planBudget, and none counts past the budget.
                    var planEnd = now.plus(config.getRuns().getPlanBudget().toJavaDuration());
                    var stopAt = planEnd.isBefore(budgetEnd) ? planEnd : budgetEnd;
                    long began = System.nanoTime();
                    return Flux.fromIterable(due)
                            .takeWhile(candidate -> now.plusNanos(System.nanoTime() - began).isBefore(stopAt))
                            .filterWhen(candidate -> countAndQueue(candidate, night, queue, current))
                            .take(free)
                            .count()
                            .map(Long::intValue);
                })
                .doOnNext(queued -> log.info("Topics night '{}' plan done, nightly updates queued '{}'", night.date(),
                        queued));
    }

    /**
     * The projects of the workspaces with a saved list, not looked at tonight and with no run in flight. Writes the
     * skips that need no count, and returns the rest in {@link #queueOrder}.
     */
    private List<NightCandidate> due(TopicsNight night, Collection<String> workspaceIds, Tally current) {
        current.begin(workspaceIds);
        var rows = template.inTransaction(READ_ONLY,
                handle -> handle.attach(TopicProjectDAO.class).findNightDue(workspaceIds, night.date()));
        var nightly = config.getNightly();
        var pausedSince = night.date().minusDays(nightly.getPauseNights()).atStartOfDay(ZoneOffset.UTC).toInstant();
        var skipped = new EnumMap<NightSkipReason, List<TopicProjectKey>>(NightSkipReason.class);
        var due = new ArrayList<NightCandidate>();
        rows.stream().collect(Collectors.groupingBy(TopicProject::workspaceId))
                .forEach((workspaceId, listed) -> {
                    // A deleted project keeps its rows until the sweep: run only projects that exist.
                    var existing = projectService.findByIds(workspaceId, listed.stream().map(TopicProject::projectId)
                            .collect(Collectors.toSet())).stream().map(Project::id).collect(Collectors.toSet());
                    for (var row : listed) {
                        skipReason(row, existing, night, nightly.getPauseFailures(), pausedSince).ifPresentOrElse(
                                reason -> skipped.computeIfAbsent(reason, key -> new ArrayList<>())
                                        .add(new TopicProjectKey(workspaceId, row.projectId())),
                                () -> due.add(NightCandidate.builder().workspaceId(workspaceId)
                                        .projectId(row.projectId()).windowUntil(row.windowUntil()).build()));
                    }
                });
        skipped.forEach((reason, keys) -> record(night, keys, NightDecision.SKIP, reason, current));
        due.sort(queueOrder());
        return due;
    }

    /** Why the night skips the project without counting its window; empty when it counts it. */
    private static Optional<NightSkipReason> skipReason(TopicProject row, Set<UUID> existing, TopicsNight night,
            int pauseFailures, Instant pausedSince) {
        if (!existing.contains(row.projectId())) {
            return Optional.of(NightSkipReason.DELETED);
        }
        if (covers(row, night.windowTo())) {
            return Optional.of(NightSkipReason.ALREADY_SAVED);
        }
        return paused(row, pauseFailures, pausedSince) ? Optional.of(NightSkipReason.PAUSED) : Optional.empty();
    }

    /**
     * Whether the project's last {@code failures} runs or more failed in a row, the latest started at or after
     * {@code since}. A run whose pod died counts once the planner's reconciler records it {@code abandoned}, which each
     * plan does before its night pass.
     */
    static boolean paused(@NonNull TopicProject row, int failures, @NonNull Instant since) {
        return row.failedRuns() >= failures && row.runStartedAt() != null && !row.runStartedAt().isBefore(since);
    }

    /** Counts the project's window and queues it; true when it queued it. */
    private Mono<Boolean> countAndQueue(NightCandidate candidate, TopicsNight night,
            Function<TopicsRunPlanner.Planned, Mono<UUID>> queue, Tally current) {
        var key = new TopicProjectKey(candidate.workspaceId(), candidate.projectId());
        return candidates.windowVectors(candidate.workspaceId(), candidate.projectId(), night.date())
                .onErrorResume(error -> {
                    log.warn("Topics night count read failed, workspace '{}', project '{}'; it runs the next night",
                            candidate.workspaceId(), candidate.projectId(), error);
                    // Empty: no count, so nothing to queue.
                    return jdbi(() -> {
                        record(night, List.of(key), NightDecision.COUNT_FAILED, null, current);
                        return null;
                    });
                })
                .flatMap(vectors -> {
                    if (vectors < config.getRun().getMinVectors()) {
                        return jdbi(() -> {
                            record(night, List.of(key), NightDecision.SKIP, NightSkipReason.SMALL, current);
                            return false;
                        });
                    }
                    return queue.apply(TopicsRunPlanner.Planned.builder().workspaceId(candidate.workspaceId())
                            .projectId(candidate.projectId()).mode(RunMode.UPDATE).night(night.date())
                            .windowTraces(vectors).build())
                            .flatMap(runId -> jdbi(() -> {
                                log.info("Topics night '{}' queued run '{}', workspace '{}', project '{}', vectors "
                                        + "'{}'", night.date(), runId, candidate.workspaceId(),
                                        candidate.projectId(), vectors);
                                record(night, List.of(key), NightDecision.UPDATE, null, current);
                                current.queued(Instant.now());
                                return true;
                            }))
                            .onErrorResume(error -> {
                                log.warn("Topics night '{}' could not queue project '{}'; a later plan tries again",
                                        night.date(), candidate.projectId(), error);
                                return Mono.just(false);
                            });
                })
                // Not counted, or it got a run meanwhile: a later plan decides it.
                .defaultIfEmpty(false);
    }

    /**
     * After the budget: records the projects with a list the night did not look at as not reached (once per night,
     * whichever plan writes it), then, once per night on this leader, counts the night finished and logs it.
     */
    private Mono<Void> end(TopicsNight night, Collection<String> workspaceIds, Tally current) {
        return jdbi(() -> {
            var left = template.inTransaction(READ_ONLY, handle -> handle.attach(TopicProjectDAO.class)
                    .findNotReached(workspaceIds, night.date()));
            if (!left.isEmpty()) {
                current.begin(workspaceIds);
                record(night, left, NightDecision.NOT_REACHED, null, current);
            }
            current.end(night, config.getNightly().getBudget().toJavaDuration());
            return null;
        }).then();
    }

    /** The tally of {@code night}, a new one when the night changed. */
    private Tally tally(TopicsNight night) {
        return tally.updateAndGet(last -> last != null && last.date.equals(night.date()) ? last : new Tally(night));
    }

    /** Whether the saved list's window already ends at or after {@code windowTo}: the night would count it twice. */
    private static boolean covers(TopicProject row, Instant windowTo) {
        return row.windowUntil() != null && !row.windowUntil().isBefore(windowTo);
    }

    /**
     * The night's queue, stalest first: the oldest {@code window_until}, so a project a night did not reach goes first
     * the next night. A project with no {@code window_until} sorts first; among equals, by project id.
     */
    static Comparator<NightCandidate> queueOrder() {
        return Comparator.comparing(NightCandidate::windowUntil, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(NightCandidate::projectId);
    }

    /**
     * Counts, logs and writes one decision on several projects: one log line and one write per workspace. A write that
     * fails is logged and the pass goes on: the first failed write of a night is a WARN with its error, the others
     * DEBUG, and the night's end WARNs how many failed.
     */
    private void record(TopicsNight night, Collection<TopicProjectKey> keys, NightDecision decision,
            NightSkipReason reason,
            Tally current) {
        keys.stream().collect(Collectors.groupingBy(TopicProjectKey::workspaceId,
                Collectors.mapping(TopicProjectKey::projectId, Collectors.toSet())))
                .forEach((workspaceId, projectIds) -> {
                    var reasonValue = reason == null ? null : reason.getValue();
                    log.info("Topics night '{}' decided, workspace '{}', projects '{}', decision '{}', reason '{}'",
                            night.date(), workspaceId, projectIds, decision.getValue(), reasonValue);
                    metrics.nightlyDecision(workspace(workspaceId), decision, reason, projectIds.size());
                    current.decided(decision, projectIds.size());
                    try {
                        template.inTransaction(WRITE, handle -> {
                            var dao = handle.attach(TopicProjectDAO.class);
                            return decision == NightDecision.NOT_REACHED
                                    ? dao.markNotReached(workspaceId, projectIds, night.date())
                                    : dao.markLooked(workspaceId, projectIds, night.date());
                        });
                    } catch (RuntimeException exception) {
                        if (current.writeFailed() == 1) {
                            log.warn("Topics night '{}' could not write its decision '{}', workspace '{}'",
                                    night.date(), decision.getValue(), workspaceId, exception);
                        } else {
                            log.debug("Topics night '{}' could not write its decision '{}', workspace '{}'",
                                    night.date(), decision.getValue(), workspaceId, exception);
                        }
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

    /**
     * What this leader saw of one night. Plans run one at a time, but their steps hop threads, so every method is
     * synchronized.
     */
    private final class Tally {

        private final LocalDate date;
        private final Instant start;
        private final Set<String> begun = new HashSet<>();
        private final Map<NightDecision, Long> decided = new EnumMap<>(NightDecision.class);
        private Instant lastQueuedAt;
        private int writeFailures;
        private boolean ended;

        Tally(TopicsNight night) {
            this.date = night.date();
            this.start = night.start();
            for (var outcome : NightOutcome.values()) {
                metrics.night(outcome, 0);
            }
        }

        /** Sets every decision's series of a workspace new tonight at 0, so its first decision is seen as a rise. */
        synchronized void begin(Collection<String> workspaceIds) {
            for (var workspaceId : workspaceIds) {
                if (!begun.add(workspaceId)) {
                    continue;
                }
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
        }

        synchronized void decided(NightDecision decision, long projects) {
            decided.merge(decision, projects, Long::sum);
        }

        synchronized void queued(Instant at) {
            lastQueuedAt = at;
        }

        synchronized int writeFailed() {
            return ++writeFailures;
        }

        /** The night's end, once: counted finished, its budget use recorded when this leader saw its queue, logged. */
        synchronized void end(TopicsNight night, Duration budget) {
            if (ended) {
                return;
            }
            ended = true;
            metrics.night(NightOutcome.FINISHED, 1);
            long notReached = decided.getOrDefault(NightDecision.NOT_REACHED, 0L);
            if (notReached > 0) {
                metrics.budgetUsed(budget);
            } else if (lastQueuedAt != null) {
                var used = Duration.between(start, lastQueuedAt);
                metrics.budgetUsed(used.compareTo(budget) < 0 ? used : budget);
            }
            if (writeFailures > 0) {
                log.warn("Topics night '{}' could not write '{}' of its decisions to topic_projects", date,
                        writeFailures);
            }
            log.info("Topics night '{}' ended, started '{}', queued '{}', skipped '{}', count failed '{}', not reached "
                    + "'{}'", night.date(), start, decided.getOrDefault(NightDecision.UPDATE, 0L),
                    decided.getOrDefault(NightDecision.SKIP, 0L), decided.getOrDefault(NightDecision.COUNT_FAILED, 0L),
                    notReached);
        }
    }
}

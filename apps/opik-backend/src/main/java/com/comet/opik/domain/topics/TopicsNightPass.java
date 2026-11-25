package com.comet.opik.domain.topics;

import com.comet.opik.api.error.ConflictException;
import com.comet.opik.domain.WorkspaceNameService;
import com.comet.opik.domain.topics.TopicProject.RunMode;
import com.comet.opik.infrastructure.TopicsConfig;
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
 * topic list, as runs of mode {@code nightly_update} that share {@code topics.runs.maxInFlight} with the first builds.
 *
 * <p>The night is the UTC date of the {@code topics.nightly.schedule} fire that starts it; it starts at its first fire
 * that day. From the night's start until {@code topics.nightly.budget} after it, each plan decides on the projects the
 * night has not looked at yet ({@code last_night} before the night) and that have no run in flight. A project is
 * skipped when it is deleted, has no list, its list already covers the night, or its last {@value #PAUSE_FAILURES}
 * attempts failed within {@value #PAUSE_NIGHTS} nights. Then, while slots are free, the rest are counted one by one in
 * {@link #queueOrder}: one under {@code topics.build.minVectors} is skipped, one whose count fails is left for the
 * next night, and the others are queued with their count. A project with a run in flight waits for a later plan. No
 * project is queued after the budget: the first plan after it records every project the night did not look at as not
 * reached.
 *
 * <p>Each decision is counted in {@code topics.nightly.decisions}, logged, and written to the project's row: one that
 * looked at it sets {@code last_night}, so later plans of the night leave it alone; a night that did not reach it adds
 * the night to {@code not_reached_nights}, once. The state gauges ({@link TopicsNightlyGauges}) read those fields. The
 * night's end is counted in {@code topics.nightly.nights} and logged by the leader that saw it; a leader that took
 * over during the night logs only what it decided.
 */
@Slf4j
@Singleton
class TopicsNightPass {

    /**
     * A project whose last {@value #PAUSE_FAILURES} attempts failed is skipped for {@value #PAUSE_NIGHTS} nights from
     * the night of the last failure, so a project that keeps failing does not take the night's budget every night.
     */
    static final int PAUSE_FAILURES = 3;
    static final int PAUSE_NIGHTS = 7;

    private final TopicsConfig config;
    private final TransactionTemplate template;
    private final TopicWorkspaceService workspaceService;
    private final TopicsFirstBuildCandidates candidates;
    private final TopicsMetrics metrics;
    private final WorkspaceNameService workspaceNames;
    /** What this leader saw of the last night it planned: for the night's end, its metrics and its log line. */
    private final AtomicReference<Tally> tally = new AtomicReference<>();

    @Inject
    TopicsNightPass(@NonNull @Config("topics") TopicsConfig config, @NonNull TransactionTemplate template,
            @NonNull TopicWorkspaceService workspaceService, @NonNull TopicsFirstBuildCandidates candidates,
            @NonNull TopicsMetrics metrics, @NonNull WorkspaceNameService workspaceNames) {
        this.config = config;
        this.template = template;
        this.workspaceService = workspaceService;
        this.candidates = candidates;
        this.metrics = metrics;
        this.workspaceNames = workspaceNames;
    }

    /** A project the night counts and may queue. */
    @Builder(toBuilder = true)
    record NightCandidate(@NonNull String workspaceId, @NonNull UUID projectId, Instant windowUntil) {
    }

    /** What the pass reads of one workspace's projects in one transaction; it decides on them after it. */
    private record Listed(List<TopicProject> rows, Set<UUID> existing, Set<UUID> paused) {
    }

    /**
     * One plan's night pass at {@code now}: queues up to {@code free} nightly updates through {@code queue} and
     * returns how many it queued. Nothing when the nightly job is off or no night is on. A read that fails is logged
     * and counted as a failed night; the next plan goes on.
     */
    Mono<Integer> plan(@NonNull Collection<String> workspaceIds, @NonNull Instant now, int free,
            @NonNull Function<TopicsRunPlanner.Planned, Mono<UUID>> queue) {
        var nightly = config.getNightly();
        if (!nightly.isEnabled() || workspaceIds.isEmpty()) {
            return Mono.just(0);
        }
        var found = TopicsNight.current(nightly.getSchedule(), now);
        if (found.isEmpty()) {
            return Mono.just(0);
        }
        var night = found.get();
        var budgetEnd = night.start().plus(nightly.getBudget().toJavaDuration());
        var current = tally(night);
        var pass = now.isBefore(budgetEnd)
                ? decide(night, workspaceIds, now, budgetEnd, free, queue, current)
                : end(night, workspaceIds, current).thenReturn(0);
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
     * @throws ConflictException when the nightly job is off, the workspace is not opted in, the project is gone or has
     *                           no list, its list already covers the night, its window has too few vectors, or it has
     *                           a run in flight
     */
    Mono<UUID> byHand(@NonNull String workspaceId, @NonNull UUID projectId, @NonNull LocalDate night,
            @NonNull Function<TopicsRunPlanner.Planned, Mono<UUID>> queue) {
        var windowTo = TopicsNight.windowTo(night);
        return jdbi(() -> {
            if (!config.getNightly().isEnabled()) {
                throw new ConflictException("The topics nightly job is off: topics.nightly.enabled is false");
            }
            if (!workspaceService.isEnabled(workspaceId)) {
                throw new ConflictException(("Workspace '%s' is not opted in to topics: it needs to be in "
                        + "topics.enabledWorkspaces and enabled in ai_label_workspaces").formatted(workspaceId));
            }
            var rows = template.inTransaction(READ_ONLY, handle -> handle.attach(TopicDAO.class)
                    .findExistingProjectIds(workspaceId, Set.of(projectId)).isEmpty()
                            ? List.<TopicProject>of()
                            : handle.attach(TopicProjectDAO.class).findProjects(workspaceId, Set.of(projectId)));
            var row = rows.stream().filter(found -> found.listRunId() != null).findFirst()
                    .orElseThrow(() -> new ConflictException(
                            "Project '%s' has no topic list: its first build makes one".formatted(projectId)));
            if (covers(row, windowTo)) {
                throw new ConflictException(("Project '%s' has a list saved with its window until '%s', at or after "
                        + "the night '%s'").formatted(projectId, row.windowUntil(), night));
            }
            return row;
        })
                .flatMap(__ -> candidates.windowVectors(workspaceId, projectId, night))
                .flatMap(vectors -> {
                    int min = config.getBuild().getMinVectors();
                    if (vectors < min) {
                        return Mono.error(new ConflictException(
                                "Project '%s' has '%d' vectors in the window until '%s', the minimum is '%d'"
                                        .formatted(projectId, vectors, night, min)));
                    }
                    return queue.apply(TopicsRunPlanner.Planned.builder().workspaceId(workspaceId)
                            .projectId(projectId).mode(RunMode.NIGHTLY_UPDATE).night(night).windowTraces(vectors)
                            .build())
                            .switchIfEmpty(Mono.error(() -> new ConflictException(
                                    "A topics run of project '%s' is in flight".formatted(projectId))));
                })
                .doOnNext(runId -> log.info("Topics run '{}' queued by hand, mode '{}', workspace '{}', project '{}', "
                        + "night '{}'", runId, RunMode.NIGHTLY_UPDATE.getValue(), workspaceId, projectId, night));
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
     * The projects of the workspaces with a topic, not looked at tonight and with no run in flight. Writes the skips
     * that need no count, and returns the rest in {@link #queueOrder}.
     */
    private List<NightCandidate> due(TopicsNight night, Collection<String> workspaceIds, Tally current) {
        current.begin(workspaceIds);
        var listed = template.inTransaction(READ_ONLY,
                handle -> handle.attach(TopicDAO.class).findProjectsWithTopics(workspaceIds));
        var pausedSince = night.date().minusDays(PAUSE_NIGHTS).atStartOfDay(ZoneOffset.UTC).toInstant();
        var skipped = new EnumMap<NightSkipReason, List<TopicProjectKey>>(NightSkipReason.class);
        var due = new ArrayList<NightCandidate>();
        listed.stream().collect(Collectors.groupingBy(TopicProjectKey::workspaceId,
                Collectors.mapping(TopicProjectKey::projectId, Collectors.toSet())))
                .forEach((workspaceId, ids) -> {
                    var read = template.inTransaction(READ_ONLY, handle -> {
                        var projectDAO = handle.attach(TopicProjectDAO.class);
                        // A deleted project keeps its rows until the sweep: run only projects that exist.
                        return new Listed(projectDAO.findProjects(workspaceId, ids),
                                handle.attach(TopicDAO.class).findExistingProjectIds(workspaceId, ids),
                                projectDAO.findPausedProjectIds(workspaceId, ids, PAUSE_FAILURES, pausedSince));
                    });
                    // A project with no row is not in the rows: nothing to write the decision on. A saved list
                    // always has one.
                    for (var row : read.rows()) {
                        if ((row.lastNight() != null && !row.lastNight().isBefore(night.date()))
                                || (row.runStatus() != null && row.runStatus().inFlight())) {
                            continue;
                        }
                        skipReason(row, read, night).ifPresentOrElse(
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
    private static Optional<NightSkipReason> skipReason(TopicProject row, Listed read, TopicsNight night) {
        if (!read.existing().contains(row.projectId())) {
            return Optional.of(NightSkipReason.DELETED);
        }
        if (row.listRunId() == null) {
            return Optional.of(NightSkipReason.NO_LIST);
        }
        if (covers(row, night.windowTo())) {
            return Optional.of(NightSkipReason.ALREADY_SAVED);
        }
        return read.paused().contains(row.projectId()) ? Optional.of(NightSkipReason.PAUSED) : Optional.empty();
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
                    if (vectors < config.getBuild().getMinVectors()) {
                        return jdbi(() -> {
                            record(night, List.of(key), NightDecision.SKIP, NightSkipReason.SMALL, current);
                            return false;
                        });
                    }
                    return queue.apply(TopicsRunPlanner.Planned.builder().workspaceId(candidate.workspaceId())
                            .projectId(candidate.projectId()).mode(RunMode.NIGHTLY_UPDATE).night(night.date())
                            .windowTraces(vectors).build())
                            .flatMap(runId -> jdbi(() -> {
                                log.info("Topics night '{}' queued run '{}', workspace '{}', project '{}', vectors "
                                        + "'{}'", night.date(), runId, candidate.workspaceId(),
                                        candidate.projectId(), vectors);
                                record(night, List.of(key), NightDecision.NIGHTLY_UPDATE, null, current);
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
            var left = template.inTransaction(READ_ONLY, handle -> handle.attach(TopicProjectNightDAO.class)
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
                            var dao = handle.attach(TopicProjectNightDAO.class);
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
                    + "'{}'", night.date(), start, decided.getOrDefault(NightDecision.NIGHTLY_UPDATE, 0L),
                    decided.getOrDefault(NightDecision.SKIP, 0L), decided.getOrDefault(NightDecision.COUNT_FAILED, 0L),
                    notReached);
        }
    }
}

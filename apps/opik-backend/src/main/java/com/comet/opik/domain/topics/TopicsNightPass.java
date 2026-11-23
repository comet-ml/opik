package com.comet.opik.domain.topics;

import com.comet.opik.api.error.ConflictException;
import com.comet.opik.domain.WorkspaceNameService;
import com.comet.opik.domain.topics.TopicProject.RunMode;
import com.comet.opik.infrastructure.TopicsConfig;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.quartz.CronExpression;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.text.ParseException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Date;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TimeZone;
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
     * A project whose last {@value #PAUSE_FAILURES} attempts failed is skipped for {@value #PAUSE_NIGHTS} nights from the
     * night of the last failure, so a project that keeps failing does not take the night's budget every night.
     */
    static final int PAUSE_FAILURES = 3;
    static final int PAUSE_NIGHTS = 7;

    private final TopicsConfig config;
    private final TransactionTemplate template;
    private final TopicWorkspaceService workspaceService;
    private final TopicsFirstBuildCandidates candidates;
    private final TopicsMetrics metrics;
    private final Function<String, String> names;
    /** What this leader saw of the last night it planned: for the night's end, its metrics and its log line. */
    private final AtomicReference<Tally> tally = new AtomicReference<>();

    @Inject
    TopicsNightPass(@NonNull @Config("topics") TopicsConfig config, @NonNull TransactionTemplate template,
            @NonNull TopicWorkspaceService workspaceService, @NonNull TopicsFirstBuildCandidates candidates,
            @NonNull TopicsMetrics metrics, @NonNull WorkspaceNameService workspaceNames) {
        this(config, template, workspaceService, candidates, metrics, workspaceNames::nameOrId);
    }

    TopicsNightPass(@NonNull TopicsConfig config, @NonNull TransactionTemplate template,
            @NonNull TopicWorkspaceService workspaceService, @NonNull TopicsFirstBuildCandidates candidates,
            @NonNull TopicsMetrics metrics, @NonNull Function<String, String> names) {
        this.config = config;
        this.template = template;
        this.workspaceService = workspaceService;
        this.candidates = candidates;
        this.metrics = metrics;
        this.names = names;
    }

    /** A night: its UTC date, whose start ends its window, and the schedule's first fire that day, its start. */
    record Night(@NonNull LocalDate date, @NonNull Instant start) {

        Instant windowTo() {
            return date.atStartOfDay(ZoneOffset.UTC).toInstant();
        }
    }

    /** A project the night counts and may queue. */
    record Candidate(@NonNull String workspaceId, @NonNull UUID projectId, Instant windowUntil) {
    }

    /** What the night did with a project: the {@code decision} attribute of {@code topics.nightly.decisions}. */
    enum Decision {
        /** Queued, with its window's count. */
        NIGHTLY_UPDATE,
        SKIP,
        /** The budget ended before the project. */
        NOT_REACHED,
        /** The project's window count read failed; it runs the next night. */
        COUNT_FAILED;

        String value() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** Why the night skipped a project: the {@code reason} attribute of a skip. */
    enum SkipReason {
        /** Under {@code topics.build.minVectors}. */
        SMALL,
        DELETED,
        PAUSED,
        /** Its saved list's window already ends at or after the night's start. */
        ALREADY_SAVED,
        /** No list. */
        NO_LIST;

        String value() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    enum NightOutcome {
        FINISHED,
        /** A read of the night's projects failed; the next plan tries again. */
        FAILED;

        String value() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * The night {@code now} falls in: the latest UTC date, today or yesterday, on which the schedule fired at or before
     * {@code now}, with its first fire that day. Empty when the schedule fired on neither: a night older than that is
     * past any budget.
     */
    static Optional<Night> current(@NonNull String schedule, @NonNull Instant now) {
        var cron = cron(schedule);
        var today = LocalDate.ofInstant(now, ZoneOffset.UTC);
        for (var date : List.of(today, today.minusDays(1))) {
            var dayStart = date.atStartOfDay(ZoneOffset.UTC).toInstant();
            var first = cron.getNextValidTimeAfter(Date.from(dayStart.minusSeconds(1)));
            if (first != null && LocalDate.ofInstant(first.toInstant(), ZoneOffset.UTC).equals(date)
                    && !first.toInstant().isAfter(now)) {
                return Optional.of(new Night(date, first.toInstant()));
            }
        }
        return Optional.empty();
    }

    private static CronExpression cron(String schedule) {
        try {
            var cron = new CronExpression(schedule);
            cron.setTimeZone(TimeZone.getTimeZone(ZoneOffset.UTC));
            return cron;
        } catch (ParseException exception) {
            throw new IllegalArgumentException("topics.nightly.schedule is not a Quartz cron: '%s'".formatted(schedule),
                    exception);
        }
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
        var found = current(nightly.getSchedule(), now);
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
            metrics.night(NightOutcome.FAILED.value(), 1);
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
        var windowTo = night.atStartOfDay(ZoneOffset.UTC).toInstant();
        return jdbi(() -> {
            if (!config.getNightly().isEnabled()) {
                throw new ConflictException("The topics nightly job is off: topics.nightly.enabled is false");
            }
            if (!workspaceService.isEnabled(workspaceId)) {
                throw new ConflictException(("Workspace '%s' is not opted in to topics: it needs to be in "
                        + "topics.enabledWorkspaces and enabled in ai_label_workspaces").formatted(workspaceId));
            }
            var row = template.inTransaction(READ_ONLY, handle -> handle.attach(TopicDAO.class)
                    .findExistingProjectIds(workspaceId, Set.of(projectId)).isEmpty()
                            ? Optional.<TopicProject>empty()
                            : handle.attach(TopicProjectDAO.class).findProjects(workspaceId, Set.of(projectId))
                                    .stream().findFirst());
            if (row.isEmpty() || row.get().listRunId() == null) {
                throw new ConflictException(
                        "Project '%s' has no topic list: its first build makes one".formatted(projectId));
            }
            if (covers(row.get(), windowTo)) {
                throw new ConflictException(("Project '%s' has a list saved with its window until '%s', at or after "
                        + "the night '%s'").formatted(projectId, row.get().windowUntil(), night));
            }
            return row.get();
        })
                .flatMap(__ -> candidates.windowVectors(workspaceId, projectId, night))
                .flatMap(vectors -> {
                    int min = config.getBuild().getMinVectors();
                    if (vectors < min) {
                        return Mono.error(new ConflictException(
                                "Project '%s' has '%d' vectors in the window until '%s', the minimum is '%d'"
                                        .formatted(projectId, vectors, night, min)));
                    }
                    return queue.apply(new TopicsRunPlanner.Planned(workspaceId, projectId, RunMode.NIGHTLY_UPDATE,
                            night, vectors))
                            .switchIfEmpty(Mono.error(() -> new ConflictException(
                                    "A topics run of project '%s' is in flight".formatted(projectId))));
                })
                .doOnNext(runId -> log.info("Topics run '{}' queued by hand, mode '{}', workspace '{}', project '{}', "
                        + "night '{}'", runId, RunMode.NIGHTLY_UPDATE.getValue(), workspaceId, projectId, night));
    }

    /** Within the budget: the cheap skips first, then the counts and queues while slots are free. */
    private Mono<Integer> decide(Night night, Collection<String> workspaceIds, Instant now, Instant budgetEnd,
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
                            .concatMap(candidate -> countAndQueue(candidate, night, queue, current))
                            .filter(Boolean::booleanValue)
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
    private List<Candidate> due(Night night, Collection<String> workspaceIds, Tally current) {
        current.begin(workspaceIds);
        var listed = template.inTransaction(READ_ONLY,
                handle -> handle.attach(TopicDAO.class).findProjectsWithTopics(workspaceIds));
        var pausedSince = night.date().minusDays(PAUSE_NIGHTS).atStartOfDay(ZoneOffset.UTC).toInstant();
        var skipped = new EnumMap<SkipReason, List<TopicProjectKey>>(SkipReason.class);
        var due = new ArrayList<Candidate>();
        listed.stream().collect(Collectors.groupingBy(TopicProjectKey::workspaceId,
                Collectors.mapping(TopicProjectKey::projectId, Collectors.toSet())))
                .forEach((workspaceId, ids) -> template.inTransaction(READ_ONLY, handle -> {
                    var projectDAO = handle.attach(TopicProjectDAO.class);
                    var rows = projectDAO.findProjects(workspaceId, ids).stream()
                            .collect(Collectors.toMap(TopicProject::projectId, Function.identity()));
                    // A deleted project keeps its rows until the sweep: run only projects that exist.
                    var existing = handle.attach(TopicDAO.class).findExistingProjectIds(workspaceId, ids);
                    var paused = projectDAO.findPausedProjectIds(workspaceId, ids, PAUSE_FAILURES, pausedSince);
                    for (var projectId : ids) {
                        var row = rows.get(projectId);
                        // No row: nothing to write the decision on. A saved list always has one.
                        if (row == null || (row.lastNight() != null && !row.lastNight().isBefore(night.date()))
                                || (row.runStatus() != null && row.runStatus().inFlight())) {
                            continue;
                        }
                        var reason = !existing.contains(projectId)
                                ? SkipReason.DELETED
                                : row.listRunId() == null
                                        ? SkipReason.NO_LIST
                                        : covers(row, night.windowTo())
                                                ? SkipReason.ALREADY_SAVED
                                                : paused.contains(projectId) ? SkipReason.PAUSED : null;
                        if (reason == null) {
                            due.add(new Candidate(workspaceId, projectId, row.windowUntil()));
                        } else {
                            skipped.computeIfAbsent(reason, key -> new ArrayList<>())
                                    .add(new TopicProjectKey(workspaceId, projectId));
                        }
                    }
                    return null;
                }));
        skipped.forEach((reason, keys) -> record(night, keys, Decision.SKIP, reason, current));
        due.sort(queueOrder());
        return due;
    }

    /** Counts the project's window and queues it; true when it queued it. */
    private Mono<Boolean> countAndQueue(Candidate candidate, Night night,
            Function<TopicsRunPlanner.Planned, Mono<UUID>> queue, Tally current) {
        var key = new TopicProjectKey(candidate.workspaceId(), candidate.projectId());
        return candidates.windowVectors(candidate.workspaceId(), candidate.projectId(), night.date())
                .map(Optional::of)
                .onErrorResume(error -> {
                    log.warn("Topics night count read failed, workspace '{}', project '{}'; it runs the next night",
                            candidate.workspaceId(), candidate.projectId(), error);
                    return jdbi(() -> {
                        record(night, List.of(key), Decision.COUNT_FAILED, null, current);
                        return Optional.empty();
                    });
                })
                .flatMap(vectors -> {
                    if (vectors.isEmpty()) {
                        return Mono.just(false);
                    }
                    if (vectors.get() < config.getBuild().getMinVectors()) {
                        return jdbi(() -> {
                            record(night, List.of(key), Decision.SKIP, SkipReason.SMALL, current);
                            return false;
                        });
                    }
                    return queue.apply(new TopicsRunPlanner.Planned(candidate.workspaceId(), candidate.projectId(),
                            RunMode.NIGHTLY_UPDATE, night.date(), vectors.get()))
                            .flatMap(runId -> jdbi(() -> {
                                log.info("Topics night '{}' queued run '{}', workspace '{}', project '{}', vectors "
                                        + "'{}'", night.date(), runId, candidate.workspaceId(),
                                        candidate.projectId(), vectors.get());
                                record(night, List.of(key), Decision.NIGHTLY_UPDATE, null, current);
                                current.queued(Instant.now());
                                return true;
                            }))
                            // It got a run meanwhile: a later plan decides it.
                            .defaultIfEmpty(false)
                            .onErrorResume(error -> {
                                log.warn("Topics night '{}' could not queue project '{}'; a later plan tries again",
                                        night.date(), candidate.projectId(), error);
                                return Mono.just(false);
                            });
                });
    }

    /**
     * After the budget: records the projects with a list the night did not look at as not reached (once per night,
     * whichever plan writes it), then, once per night on this leader, counts the night finished and logs it.
     */
    private Mono<Void> end(Night night, Collection<String> workspaceIds, Tally current) {
        return jdbi(() -> {
            var left = template.inTransaction(READ_ONLY, handle -> handle.attach(TopicProjectNightDAO.class)
                    .findNotReached(workspaceIds, night.date()));
            if (!left.isEmpty()) {
                current.begin(workspaceIds);
                record(night, left, Decision.NOT_REACHED, null, current);
            }
            current.end(night, config.getNightly().getBudget().toJavaDuration());
            return null;
        }).then();
    }

    /** The tally of {@code night}, a new one when the night changed. */
    private Tally tally(Night night) {
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
    static Comparator<Candidate> queueOrder() {
        return Comparator.comparing(Candidate::windowUntil, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(Candidate::projectId);
    }

    /**
     * Counts, logs and writes one decision on several projects: one log line and one write per workspace. A write that
     * fails is logged and the pass goes on: the first failed write of a night is a WARN with its error, the others
     * DEBUG, and the night's end WARNs how many failed.
     */
    private void record(Night night, Collection<TopicProjectKey> keys, Decision decision, SkipReason reason,
            Tally current) {
        keys.stream().collect(Collectors.groupingBy(TopicProjectKey::workspaceId,
                Collectors.mapping(TopicProjectKey::projectId, Collectors.toSet())))
                .forEach((workspaceId, projectIds) -> {
                    var reasonValue = reason == null ? null : reason.value();
                    log.info("Topics night '{}' decided, workspace '{}', projects '{}', decision '{}', reason '{}'",
                            night.date(), workspaceId, projectIds, decision.value(), reasonValue);
                    metrics.nightlyDecision(workspace(workspaceId), decision.value(), reasonValue, projectIds.size());
                    current.decided(decision, projectIds.size());
                    try {
                        template.inTransaction(WRITE, handle -> {
                            var dao = handle.attach(TopicProjectNightDAO.class);
                            return decision == Decision.NOT_REACHED
                                    ? dao.markNotReached(workspaceId, projectIds, night.date())
                                    : dao.markLooked(workspaceId, projectIds, night.date());
                        });
                    } catch (RuntimeException exception) {
                        if (current.writeFailed() == 1) {
                            log.warn("Topics night '{}' could not write its decision '{}', workspace '{}'",
                                    night.date(), decision.value(), workspaceId, exception);
                        } else {
                            log.debug("Topics night '{}' could not write its decision '{}', workspace '{}'",
                                    night.date(), decision.value(), workspaceId, exception);
                        }
                    }
                });
    }

    private TopicsMetrics.Workspace workspace(String workspaceId) {
        try {
            return new TopicsMetrics.Workspace(workspaceId, names.apply(workspaceId));
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
        private final Map<Decision, Long> decided = new EnumMap<>(Decision.class);
        private Instant lastQueuedAt;
        private int writeFailures;
        private boolean ended;

        Tally(Night night) {
            this.date = night.date();
            this.start = night.start();
            for (var outcome : NightOutcome.values()) {
                metrics.night(outcome.value(), 0);
            }
        }

        /** Sets every decision's series of a workspace new tonight at 0, so its first decision is seen as a rise. */
        synchronized void begin(Collection<String> workspaceIds) {
            for (var workspaceId : workspaceIds) {
                if (!begun.add(workspaceId)) {
                    continue;
                }
                var workspace = workspace(workspaceId);
                for (var decision : Decision.values()) {
                    if (decision != Decision.SKIP) {
                        metrics.nightlyDecision(workspace, decision.value(), null, 0);
                    }
                }
                for (var reason : SkipReason.values()) {
                    metrics.nightlyDecision(workspace, Decision.SKIP.value(), reason.value(), 0);
                }
            }
        }

        synchronized void decided(Decision decision, long projects) {
            decided.merge(decision, projects, Long::sum);
        }

        synchronized void queued(Instant at) {
            lastQueuedAt = at;
        }

        synchronized int writeFailed() {
            return ++writeFailures;
        }

        /** The night's end, once: counted finished, its budget use recorded when this leader saw its queue, logged. */
        synchronized void end(Night night, Duration budget) {
            if (ended) {
                return;
            }
            ended = true;
            metrics.night(NightOutcome.FINISHED.value(), 1);
            long notReached = decided.getOrDefault(Decision.NOT_REACHED, 0L);
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
                    + "'{}'", night.date(), start, decided.getOrDefault(Decision.NIGHTLY_UPDATE, 0L),
                    decided.getOrDefault(Decision.SKIP, 0L), decided.getOrDefault(Decision.COUNT_FAILED, 0L),
                    notReached);
        }
    }
}

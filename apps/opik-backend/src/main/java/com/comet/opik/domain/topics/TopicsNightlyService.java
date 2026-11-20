package com.comet.opik.domain.topics;

import com.comet.opik.api.error.ConflictException;
import com.comet.opik.infrastructure.TopicsConfig;
import com.comet.opik.infrastructure.lock.LockService;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.ServiceUnavailableException;
import lombok.Builder;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;
import static com.comet.opik.infrastructure.lock.LockService.Lock;

/**
 * One night of the nightly update: updates the topic list of each project that has one, one project at a time.
 *
 * <p>A project runs when its workspace is opted in to topics (and ai-labels), it has a live topic, and its window (the
 * {@code topics.build.windowDays} whole UTC days before the night) holds at least {@code topics.build.minVectors} traces
 * with a vector. A project without a list is the first-build job's ({@link TopicsFirstBuildService}). Projects run in
 * {@link #queueOrder}; none starts after {@code topics.nightly.budget}, and those left run the next night.
 *
 * <p>Each decision on a project is counted in {@code topics.nightly.decisions}, logged, and written to the project's
 * {@code topic_projects} row: a night that reached it sets {@code last_night}, one that did not adds to
 * {@code not_reached_nights}. The state gauges ({@link TopicsNightlyGauges}) read those fields; each night is counted in
 * {@code topics.nightly.nights} by outcome.
 */
@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class TopicsNightlyService {

    /** The night's lock: one night at a time across the cluster, scheduled or started by hand. */
    public static final Lock NIGHT_LOCK = new Lock("topics:nightly:lock");
    /**
     * How long a date's run lock lasts: {@link #runLock} is taken for a night and never released, so a trigger that
     * fires later for the same date skips.
     */
    public static final Duration RUN_SLOT = Duration.ofDays(1);

    /** The lock that marks a date's night as run, by the scheduled job or by hand. */
    public static Lock runLock(@NonNull LocalDate night) {
        return new Lock("topics:nightly:run:" + night);
    }

    /** The night lock's time after its last run: the projects the budget did not reach are written then. */
    static final Duration LEASE_TAIL = Duration.ofMinutes(5);

    /** How long a night started by hand waits to learn whether it got the night lock. */
    private static final Duration LOCK_WAIT = Duration.ofSeconds(10);

    /**
     * A project whose last {@value #PAUSE_FAILURES} runs failed is skipped for {@value #PAUSE_NIGHTS} nights from the
     * night of the last failure, so a project that keeps failing does not take the night's budget every night.
     */
    static final int PAUSE_FAILURES = 3;
    static final int PAUSE_NIGHTS = 7;

    private final @NonNull @Config("topics") TopicsConfig config;
    private final @NonNull TransactionTemplate template;
    private final @NonNull TopicWorkspaceService workspaceService;
    private final @NonNull TopicWindowDAO windowDAO;
    private final @NonNull TopicsProjectRunner runner;
    private final @NonNull TopicsMetrics metrics;
    private final @NonNull LockService lockService;
    /** The time the budget is measured against; tests replace it. */
    Supplier<Instant> now = Instant::now;

    /** What one night did, by project. */
    @Builder(toBuilder = true)
    public record NightResult(int saved, int failed, int skipped, int notReached) {
    }

    @Builder(toBuilder = true)
    record Candidate(@NonNull String workspaceId, @NonNull UUID projectId, long vectors) {
    }

    private record Skipped(@NonNull Candidate candidate, @NonNull SkipReason reason) {
    }

    private enum Outcome {
        SAVED,
        FAILED,
        SKIPPED
    }

    /** What the night did with a project: the {@code decision} attribute of {@code topics.nightly.decisions}. */
    enum Decision {
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
        /** Its last saved run's window already ends at or after the night's start. */
        ALREADY_SAVED,
        /** No topic left by the time its lock was taken. */
        NO_LIST,
        /** Its lock, or the pod's run slot, was held by another run. */
        LOCKED;

        String value() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    enum NightOutcome {
        FINISHED,
        /** A read of the workspaces, the lists or the projects failed. */
        FAILED;

        String value() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * How long the night's lock must last: its budget, one whole run that started just before the budget ends, and
     * {@link #LEASE_TAIL} for the decisions written after it.
     */
    public Duration lease() {
        return config.getNightly().getBudget().toJavaDuration().plus(runner.projectLease()).plus(LEASE_TAIL);
    }

    /**
     * Runs the night {@code start} falls on (UTC). The budget runs from the call, not from {@code start}, so a night
     * run late, or by hand for an earlier date, still gets all of it. The night is counted {@code finished}, or
     * {@code failed} when it throws: a read of the workspaces, the lists or the projects that fails.
     */
    public NightResult runNight(@NonNull Instant start) {
        var began = now.get();
        var night = LocalDate.ofInstant(start, ZoneOffset.UTC);
        for (var outcome : NightOutcome.values()) {
            metrics.night(outcome.value(), 0);
        }
        try {
            var result = runNight(night, began, new NightRecord(night));
            metrics.night(NightOutcome.FINISHED.value(), 1);
            return result;
        } catch (RuntimeException exception) {
            metrics.night(NightOutcome.FAILED.value(), 1);
            throw exception;
        }
    }

    private NightResult runNight(LocalDate night, Instant began, NightRecord record) {
        var budgetEnd = began.plus(config.getNightly().getBudget().toJavaDuration());
        var candidates = new ArrayList<Candidate>();
        var uncounted = new ArrayList<Candidate>();
        int skipped = eligible(night, began, budgetEnd, candidates, uncounted, record);
        log.info("Topics night '{}' started, projects to update '{}', skipped '{}', not counted '{}'", night,
                candidates.size(), skipped, uncounted.size());

        int saved = 0;
        int failed = 0;
        int notReached = uncounted.size();
        record.decide(uncounted, Decision.NOT_REACHED, null);
        var executor = Executors.newThreadPerTaskExecutor(
                Thread.ofPlatform().name("topics-nightly-", 0).daemon(true).factory());
        try {
            for (int i = 0; i < candidates.size(); i++) {
                var candidate = candidates.get(i);
                if (Thread.currentThread().isInterrupted() || !now.get().isBefore(budgetEnd)) {
                    var left = candidates.subList(i, candidates.size());
                    notReached += left.size();
                    record.decide(left, Decision.NOT_REACHED, null);
                    break;
                }
                if (!runner.acquireRun(budgetEnd)) {
                    skipped++;
                    record.decide(candidate, Decision.SKIP, SkipReason.LOCKED, null);
                    continue;
                }
                try {
                    if (!runner.lockProject(candidate.projectId())) {
                        skipped++;
                        record.decide(candidate, Decision.SKIP, SkipReason.LOCKED, null);
                        continue;
                    }
                    try {
                        // The lock can wait up to its own limit: a run must still start within the budget.
                        if (!now.get().isBefore(budgetEnd)) {
                            notReached++;
                            record.decide(candidate, Decision.NOT_REACHED, null, null);
                            continue;
                        }
                        switch (runOne(executor, candidate, night, record)) {
                            case SAVED -> saved++;
                            case FAILED -> failed++;
                            case SKIPPED -> skipped++;
                        }
                    } finally {
                        runner.unlockProject(candidate.projectId());
                    }
                } finally {
                    runner.releaseRun();
                }
            }
        } finally {
            // Not close(): it waits for every task, and a run stopped by its timeout may ignore the interrupt.
            executor.shutdownNow();
        }
        var used = Duration.between(began, now.get());
        metrics.budgetUsed(used);
        record.end();
        log.info("Topics night '{}' finished, saved '{}', failed '{}', skipped '{}', not reached '{}', seconds '{}'",
                night, saved, failed, skipped, notReached, used.toSeconds());
        return NightResult.builder().saved(saved).failed(failed).skipped(skipped).notReached(notReached).build();
    }

    /**
     * Runs the night for {@code night} now, in the background, under the night lock; it returns once the night has
     * started, and the lock is released when the night ends. False when a night is in progress (this one or the
     * scheduled one).
     *
     * @throws ConflictException           when the nightly job is off
     * @throws ServiceUnavailableException when Redis fails or does not answer within {@link #LOCK_WAIT}, with a
     *                                     {@code Retry-After}
     */
    public boolean runNightNow(@NonNull LocalDate night) {
        if (!config.getNightly().isEnabled()) {
            throw new ConflictException("The topics nightly job is off: topics.nightly.enabled is false");
        }
        var started = new CompletableFuture<Boolean>();
        lockService.bestEffortLock(
                NIGHT_LOCK,
                Mono.fromRunnable(() -> {
                    started.complete(true);
                    log.info("Topics night '{}' started by hand", night);
                    markRun(night);
                    runNight(night.atStartOfDay(ZoneOffset.UTC).toInstant());
                }).subscribeOn(Schedulers.boundedElastic()),
                Mono.fromRunnable(() -> started.complete(false)),
                lease(),
                Duration.ZERO)
                .subscribe(
                        __ -> {
                        },
                        error -> {
                            // Before the night starts, the error is the lock's: Redis failed.
                            started.completeExceptionally(error);
                            log.error("Topics night '{}' started by hand failed", night, error);
                        });
        try {
            return started.get(LOCK_WAIT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw lockUnavailable();
        } catch (ExecutionException | TimeoutException exception) {
            log.warn("Topics lock unavailable, lock '{}'", NIGHT_LOCK.key(), exception);
            throw lockUnavailable();
        }
    }

    /**
     * Takes the date's run lock, so the scheduled trigger skips a date already run by hand. Held already (the
     * scheduled night ran) is fine. Not for tomorrow: tomorrow's scheduled night still reads the rest of today. A Redis
     * failure is logged, and the night goes on.
     */
    private void markRun(LocalDate night) {
        if (night.isAfter(LocalDate.now(ZoneOffset.UTC))) {
            return;
        }
        try {
            lockService.lockUsingToken(runLock(night), RUN_SLOT).block(LOCK_WAIT);
        } catch (RuntimeException exception) {
            log.warn("Topics night '{}' could not take its run lock '{}'; the scheduled job may run the date again",
                    night, runLock(night).key(), exception);
        }
    }

    private static ServiceUnavailableException lockUnavailable() {
        return new ServiceUnavailableException(
                "The topics lock '%s' is unavailable, retry later".formatted(NIGHT_LOCK.key()),
                TopicsProjectRunner.LOCK_RETRY_AFTER_SECONDS);
    }

    /**
     * Updates the project under its lock. Skipped when its last saved run already covers the night, or when it has no
     * topic left (a run by hand or a delete since the night listed it). A run that cannot start (a read before it or
     * its start write fails) has no attempt to fail, so it is counted here, as {@code start_failed}.
     */
    private Outcome runOne(ExecutorService executor, Candidate candidate, LocalDate night, NightRecord record) {
        var workspaceId = candidate.workspaceId();
        var projectId = candidate.projectId();
        UUID runId = null;
        try {
            if (savedFor(workspaceId, projectId, night)) {
                record.decide(candidate, Decision.SKIP, SkipReason.ALREADY_SAVED, null);
                return Outcome.SKIPPED;
            }
            if (!hasTopics(workspaceId, projectId)) {
                record.decide(candidate, Decision.SKIP, SkipReason.NO_LIST, null);
                return Outcome.SKIPPED;
            }
            runId = runner.start(workspaceId, projectId, TopicProject.RunMode.NIGHTLY_UPDATE);
        } catch (RuntimeException exception) {
            log.error("Topics run could not start, project '{}'", projectId, exception);
        }
        record.decide(candidate, Decision.NIGHTLY_UPDATE, null, runId);
        if (runId == null) {
            metrics.run(workspaceId, TopicProject.RunStatus.FAILED, TopicsMetrics.ERROR_START_FAILED);
            return Outcome.FAILED;
        }
        log.info("Topics run '{}' started, mode '{}', workspace '{}', project '{}', vectors '{}'", runId,
                TopicProject.RunMode.NIGHTLY_UPDATE.getValue(), workspaceId, projectId, candidate.vectors());
        return runner.run(executor, runId, workspaceId, projectId, night, TopicProject.RunMode.NIGHTLY_UPDATE)
                ? Outcome.SAVED
                : Outcome.FAILED;
    }

    /**
     * True when the project's last saved run read a window that ends at or after the night's start: running it again
     * would count the same days twice. Read under the project's lock, so a run saved meanwhile is seen.
     */
    private boolean savedFor(String workspaceId, UUID projectId, LocalDate night) {
        var to = night.atStartOfDay(ZoneOffset.UTC).toInstant();
        return template.inTransaction(READ_ONLY,
                handle -> handle.attach(TopicDAO.class).findProjects(workspaceId, Set.of(projectId))).stream()
                .anyMatch(project -> project.windowUntil() != null && !project.windowUntil().isBefore(to));
    }

    private boolean hasTopics(String workspaceId, UUID projectId) {
        return !template.inTransaction(READ_ONLY,
                handle -> handle.attach(TopicDAO.class).findProjectsWithTopics(workspaceId, Set.of(projectId)))
                .isEmpty();
    }

    /**
     * Fills {@code candidates} with the projects to update, in {@link #queueOrder} order, and returns how many were
     * skipped: under {@code minVectors}, deleted, or paused. The projects are those of the enabled workspaces with a
     * topic. Each is counted on its own: the count's {@code max_rows_to_read} applies to one query, so a large project
     * fails only its own count, never its workspace's. A project whose count read fails is decided
     * {@code count_failed} and left for the next night. A read that fails otherwise throws, and fails the night. No
     * count starts after {@code budgetEnd}: the projects left go to {@code uncounted}, and are decided
     * {@code not_reached}. So the scan ends within the budget, and the night lock's {@link #lease} still
     * covers the night.
     */
    private int eligible(LocalDate night, Instant began, Instant budgetEnd, List<Candidate> candidates,
            List<Candidate> uncounted, NightRecord record) {
        var build = config.getBuild();
        var to = night.atStartOfDay(ZoneOffset.UTC).toInstant();
        var from = night.minusDays(build.getWindowDays()).atStartOfDay(ZoneOffset.UTC).toInstant();
        var workspaceIds = workspaceService.enabledWorkspaceIds();
        if (workspaceIds.isEmpty()) {
            return 0;
        }
        record.begin(workspaceIds);
        var listed = template.inTransaction(READ_ONLY,
                handle -> handle.attach(TopicDAO.class).findProjectsWithTopics(workspaceIds));
        var skipped = new ArrayList<Skipped>();
        var found = new ArrayList<Candidate>();
        for (var project : listed) {
            if (!now.get().isBefore(budgetEnd)) {
                uncounted.add(
                        Candidate.builder().workspaceId(project.workspaceId()).projectId(project.projectId()).build());
                continue;
            }
            try {
                long traces = windowVectors(project.workspaceId(), project.projectId(), from, to);
                var candidate = Candidate.builder().workspaceId(project.workspaceId()).projectId(project.projectId())
                        .vectors(traces).build();
                if (traces >= build.getMinVectors()) {
                    found.add(candidate);
                } else {
                    skipped.add(new Skipped(candidate, SkipReason.SMALL));
                }
            } catch (RuntimeException exception) {
                log.warn("Topics night count read failed, workspace '{}', project '{}'; it runs the next night",
                        project.workspaceId(), project.projectId(), exception);
                record.decide(Candidate.builder().workspaceId(project.workspaceId())
                        .projectId(project.projectId()).build(), Decision.COUNT_FAILED, null, null);
            }
        }
        var windowUntil = new HashMap<UUID, Instant>();
        var pausedSince = night.minusDays(PAUSE_NIGHTS).atStartOfDay(ZoneOffset.UTC).toInstant();
        var abandonedBefore = began.minus(runner.projectLease());
        Set<UUID> paused = new HashSet<>();
        // A deleted project keeps its window vectors until ClickHouse drops them: run only projects that exist.
        Set<UUID> existing = new HashSet<>();
        found.stream().collect(Collectors.groupingBy(Candidate::workspaceId,
                Collectors.mapping(Candidate::projectId, Collectors.toSet())))
                .forEach((workspaceId, ids) -> template.inTransaction(READ_ONLY, handle -> {
                    handle.attach(TopicDAO.class).findProjects(workspaceId, ids)
                            .forEach(project -> windowUntil.put(project.projectId(), project.windowUntil()));
                    paused.addAll(handle.attach(TopicRunDAO.class).findPausedProjectIds(workspaceId, ids,
                            PAUSE_FAILURES, pausedSince, abandonedBefore));
                    existing.addAll(handle.attach(TopicDAO.class).findExistingProjectIds(workspaceId, ids));
                    return null;
                }));
        for (var candidate : found) {
            if (!existing.contains(candidate.projectId())) {
                skipped.add(new Skipped(candidate, SkipReason.DELETED));
            } else if (paused.contains(candidate.projectId())) {
                skipped.add(new Skipped(candidate, SkipReason.PAUSED));
            } else {
                candidates.add(candidate);
            }
        }
        candidates.sort(queueOrder(windowUntil));
        skipped.stream().collect(Collectors.groupingBy(Skipped::reason,
                Collectors.mapping(Skipped::candidate, Collectors.toList())))
                .forEach((reason, projects) -> record.decide(projects, Decision.SKIP, reason));
        return skipped.size();
    }

    private long windowVectors(String workspaceId, UUID projectId, Instant from, Instant to) {
        return windowDAO.countByProject(workspaceId, Set.of(projectId), from, to, TopicsRebuildService.COUNT_SECONDS)
                .collectList().block()
                .stream().filter(count -> count.projectId().equals(projectId))
                .mapToLong(TopicWindowDAO.ProjectCount::traces).findFirst().orElse(0);
    }

    /**
     * The night's queue, stalest first: the oldest {@code window_until}, so a project that a night did not reach goes
     * first the next night; among equals, the fewest vectors first. A project with no {@code window_until} sorts
     * first.
     */
    static Comparator<Candidate> queueOrder(@NonNull Map<UUID, Instant> windowUntil) {
        return Comparator.<Candidate, Instant>comparing(candidate -> windowUntil.get(candidate.projectId()),
                Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparingLong(Candidate::vectors)
                .thenComparing(Candidate::projectId);
    }

    /**
     * Counts, logs and writes the night's decisions. A write that fails is logged and the night goes on: the first
     * failed write of a night is a WARN with its error, the others DEBUG, and the night's end WARNs how many failed.
     */
    private final class NightRecord {

        private final LocalDate night;
        private int writeFailures;

        NightRecord(LocalDate night) {
            this.night = night;
        }

        /** Sets every decision's series of the workspaces at 0, so the first decision of each is seen as a rise. */
        void begin(List<String> workspaceIds) {
            for (var workspaceId : workspaceIds) {
                for (var decision : Decision.values()) {
                    if (decision != Decision.SKIP) {
                        metrics.nightlyDecision(workspaceId, decision.value(), null, 0);
                    }
                }
                for (var reason : SkipReason.values()) {
                    metrics.nightlyDecision(workspaceId, Decision.SKIP.value(), reason.value(), 0);
                }
            }
        }

        /** {@code reason} is null for a decision other than skip, {@code runId} when no run started. */
        void decide(Candidate candidate, Decision decision, SkipReason reason, UUID runId) {
            log.info("Topics night '{}' decided, workspace '{}', project '{}', decision '{}', reason '{}', run '{}'",
                    night, candidate.workspaceId(), candidate.projectId(), decision.value(),
                    reason == null ? null : reason.value(), runId);
            count(candidate.workspaceId(), decision, reason, 1);
            write(candidate.workspaceId(), Set.of(candidate.projectId()), decision);
        }

        /** The same decision on several projects: one log line and one write per workspace. */
        void decide(List<Candidate> candidates, Decision decision, SkipReason reason) {
            candidates.stream().collect(Collectors.groupingBy(Candidate::workspaceId,
                    Collectors.mapping(Candidate::projectId, Collectors.toSet())))
                    .forEach((workspaceId, projectIds) -> {
                        log.info("Topics night '{}' decided, workspace '{}', projects '{}', decision '{}', reason '{}'",
                                night, workspaceId, projectIds, decision.value(),
                                reason == null ? null : reason.value());
                        count(workspaceId, decision, reason, projectIds.size());
                        write(workspaceId, projectIds, decision);
                    });
        }

        void end() {
            if (writeFailures > 0) {
                log.warn("Topics night '{}' could not write '{}' of its decisions to topic_projects", night,
                        writeFailures);
            }
        }

        private void count(String workspaceId, Decision decision, SkipReason reason, long projects) {
            metrics.nightlyDecision(workspaceId, decision.value(), reason == null ? null : reason.value(), projects);
        }

        private void write(String workspaceId, Set<UUID> projectIds, Decision decision) {
            Consumer<TopicProjectNightDAO> action = decision == Decision.NOT_REACHED
                    ? dao -> dao.markNotReached(workspaceId, projectIds)
                    : dao -> dao.markLooked(workspaceId, projectIds, night);
            try {
                template.inTransaction(WRITE, handle -> {
                    action.accept(handle.attach(TopicProjectNightDAO.class));
                    return null;
                });
            } catch (RuntimeException exception) {
                if (writeFailures++ == 0) {
                    log.warn("Topics night '{}' could not write its decision '{}', workspace '{}'", night,
                            decision.value(), workspaceId, exception);
                } else {
                    log.debug("Topics night '{}' could not write its decision '{}', workspace '{}'", night,
                            decision.value(), workspaceId, exception);
                }
            }
        }
    }
}

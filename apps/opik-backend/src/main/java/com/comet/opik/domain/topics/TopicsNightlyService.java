package com.comet.opik.domain.topics;

import com.comet.opik.api.error.ConflictException;
import com.comet.opik.domain.IdGenerator;
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
import java.util.stream.Collectors;

import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;
import static com.comet.opik.infrastructure.lock.LockService.Lock;

/**
 * One night of the nightly update: updates the topic list of each project that has one, one project at a time.
 *
 * <p>A project runs when its workspace is opted in to topics (and ai-labels), it has a live topic, and its window (the
 * {@code topics.build.windowDays} whole UTC days before the night) holds at least {@code topics.build.minVectors} traces
 * with a vector. A project without a list is the first-build job's ({@link TopicsFirstBuildService}). Under the
 * project's lock the night checks again that its last saved run does not cover the night already and that it still has
 * a live topic. Projects run stalest first: the oldest {@code window_until}, and among equals the smallest. None starts
 * after {@code topics.nightly.budget}; those left run the next night. A project whose last {@value #PAUSE_FAILURES}
 * runs failed is paused for {@value #PAUSE_NIGHTS} nights. Each run is {@link TopicsProjectRunner#run}: its own
 * thread, stopped and failed with {@code timeout} after {@code topics.build.projectTimeout}.
 *
 * <p>Each night writes its record as it goes: its {@code topic_nights} row when it starts and ends, and one
 * {@code topic_night_projects} row per project as it decides it. The state gauges ({@link TopicsNightlyGauges}) read it.
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
    private final @NonNull TopicsRebuildService rebuildService;
    private final @NonNull TopicsProjectRunner runner;
    private final @NonNull TopicsMetrics metrics;
    private final @NonNull LockService lockService;
    private final @NonNull IdGenerator idGenerator;

    /** What one night did, by project. */
    @Builder(toBuilder = true)
    public record NightResult(int saved, int failed, int skipped, int notReached) {
    }

    record Candidate(@NonNull String workspaceId, @NonNull UUID projectId, long vectors) {
    }

    /** A project the night listed and skipped, with why. */
    private record Skipped(@NonNull Candidate candidate, @NonNull String reason) {
    }

    private enum Outcome {
        SAVED,
        FAILED,
        SKIPPED
    }

    /** How long the night's lock must last: its budget, then one whole run that started just before the budget ends. */
    public Duration lease() {
        return config.getNightly().getBudget().toJavaDuration().plus(runner.projectLease());
    }

    /**
     * Runs the night {@code start} falls on (UTC). The budget runs from the call, not from {@code start}, so a night
     * run late, or by hand for an earlier date, still gets all of it. The night's record ends {@code finished}, or
     * {@code failed} when the night throws: a read of the workspaces, the lists or the projects that fails. A night
     * whose record cannot start throws before it runs anything: a rerun of the same date would otherwise add its
     * decisions to the earlier run's rows.
     */
    public NightResult runNight(@NonNull Instant start) {
        var began = Instant.now();
        var night = LocalDate.ofInstant(start, ZoneOffset.UTC);
        var record = new NightRecord(night, began);
        record.start();
        try {
            var result = runNight(night, began, record);
            record.end(TopicNight.Outcome.FINISHED);
            return result;
        } catch (RuntimeException exception) {
            record.end(TopicNight.Outcome.FAILED);
            throw exception;
        }
    }

    private NightResult runNight(LocalDate night, Instant began, NightRecord record) {
        runner.housekeeping(began);
        nightsHousekeeping(began);
        var budgetEnd = began.plus(config.getNightly().getBudget().toJavaDuration());
        var candidates = new ArrayList<Candidate>();
        var uncounted = new ArrayList<Candidate>();
        int skipped = eligible(night, budgetEnd, candidates, uncounted, record);
        log.info("Topics night '{}' started, projects to update '{}', skipped '{}', not counted '{}'", night,
                candidates.size(), skipped, uncounted.size());

        int saved = 0;
        int failed = 0;
        int notReached = 0;
        for (var candidate : uncounted) {
            notReached++;
            record.decide(candidate, TopicNightProject.NOT_REACHED, null, null);
        }
        var executor = Executors.newThreadPerTaskExecutor(
                Thread.ofPlatform().name("topics-nightly-", 0).daemon(true).factory());
        try {
            for (var candidate : candidates) {
                if (Thread.currentThread().isInterrupted() || !Instant.now().isBefore(budgetEnd)) {
                    notReached++;
                    record.decide(candidate, TopicNightProject.NOT_REACHED, null, null);
                    continue;
                }
                if (!runner.acquireRun(budgetEnd)) {
                    log.info("Topics night skipped project '{}', another run on this pod held its run slot",
                            candidate.projectId());
                    skipped++;
                    record.decide(candidate, TopicNightProject.SKIP, TopicNightProject.REASON_LOCKED, null);
                    continue;
                }
                try {
                    if (!runner.lockProject(candidate.projectId())) {
                        log.info("Topics night skipped project '{}', its lock is held by another run or Redis is down",
                                candidate.projectId());
                        skipped++;
                        record.decide(candidate, TopicNightProject.SKIP, TopicNightProject.REASON_LOCKED, null);
                        continue;
                    }
                    try {
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
        var used = Duration.between(began, Instant.now());
        metrics.budgetUsed(used);
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
     * Updates the project under its lock; its night row gets the decision and the run id. Skipped when its last saved
     * run already covers the night, or when it has no live topic left (a run by hand or a delete since the night listed
     * it). A run that cannot start (a read before it or its {@code topic_runs} insert fails) has no row to fail, so it
     * is counted here, as {@code start_failed}.
     */
    private Outcome runOne(ExecutorService executor, Candidate candidate, LocalDate night, NightRecord record) {
        var workspaceId = candidate.workspaceId();
        var projectId = candidate.projectId();
        UUID runId = null;
        try {
            if (savedFor(workspaceId, projectId, night)) {
                log.info("Topics night skipped project '{}', its last saved run already covers the night", projectId);
                record.decide(candidate, TopicNightProject.SKIP, TopicNightProject.REASON_ALREADY_SAVED, null);
                return Outcome.SKIPPED;
            }
            if (!hasLiveTopics(workspaceId, projectId)) {
                log.info("Topics night skipped project '{}', it has no live topic left", projectId);
                record.decide(candidate, TopicNightProject.SKIP, TopicNightProject.REASON_NO_LIST, null);
                return Outcome.SKIPPED;
            }
            runId = rebuildService.startRun(workspaceId, projectId, TopicRun.Mode.NIGHTLY_UPDATE);
        } catch (RuntimeException exception) {
            log.error("Topics run could not start, project '{}'", projectId, exception);
        }
        record.decide(candidate, TopicNightProject.NIGHTLY_UPDATE, null, runId);
        if (runId == null) {
            metrics.run(workspaceId, TopicRun.Status.FAILED, TopicsMetrics.ERROR_START_FAILED);
            return Outcome.FAILED;
        }
        log.info("Topics run '{}' started, mode '{}', workspace '{}', project '{}', vectors '{}'", runId,
                TopicRun.Mode.NIGHTLY_UPDATE.getValue(), workspaceId, projectId, candidate.vectors());
        return runner.run(executor, runId, workspaceId, projectId, night, TopicRun.Mode.NIGHTLY_UPDATE)
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
                handle -> handle.attach(TopicDAO.class).findStates(workspaceId, Set.of(projectId))).stream()
                .anyMatch(state -> state.windowUntil() != null && !state.windowUntil().isBefore(to));
    }

    private boolean hasLiveTopics(String workspaceId, UUID projectId) {
        return !template.inTransaction(READ_ONLY,
                handle -> handle.attach(TopicDAO.class).findProjectsWithLiveTopics(workspaceId, Set.of(projectId)))
                .isEmpty();
    }

    /**
     * Fills {@code candidates} with the projects to update, in {@link #queueOrder} order, and returns how many were
     * skipped: under {@code minVectors}, deleted, or paused. The projects are those of the eligible workspaces with a
     * live topic. Each is counted on its own: the count's {@code max_rows_to_read} applies to one query, so a large
     * project fails only its own count, never its workspace's. A project whose count read fails gets a
     * {@code count_failed} row and is left for the next night. A read that fails otherwise throws, and fails the night.
     * No count starts after {@code budgetEnd}: the projects left go to {@code uncounted}, and get a {@code not_reached}
     * row. So the scan ends within the budget, and the night lock's lease (budget + one run) still covers the night.
     */
    private int eligible(LocalDate night, Instant budgetEnd, List<Candidate> candidates, List<Candidate> uncounted,
            NightRecord record) {
        var build = config.getBuild();
        var to = night.atStartOfDay(ZoneOffset.UTC).toInstant();
        var from = night.minusDays(build.getWindowDays()).atStartOfDay(ZoneOffset.UTC).toInstant();
        var workspaceIds = workspaceService.eligibleWorkspaceIds();
        if (workspaceIds.isEmpty()) {
            return 0;
        }
        var listed = template.inTransaction(READ_ONLY,
                handle -> handle.attach(TopicDAO.class).findProjectsWithLiveTopics(workspaceIds));
        var skipped = new ArrayList<Skipped>();
        var found = new ArrayList<Candidate>();
        for (var project : listed) {
            if (!Instant.now().isBefore(budgetEnd)) {
                uncounted.add(new Candidate(project.workspaceId(), project.projectId(), 0));
                continue;
            }
            try {
                long traces = windowVectors(project.workspaceId(), project.projectId(), from, to);
                var candidate = new Candidate(project.workspaceId(), project.projectId(), traces);
                if (traces >= build.getMinVectors()) {
                    found.add(candidate);
                } else {
                    skipped.add(new Skipped(candidate, TopicNightProject.REASON_SMALL));
                }
            } catch (RuntimeException exception) {
                log.warn("Topics night count read failed, workspace '{}', project '{}'; it runs the next night",
                        project.workspaceId(), project.projectId(), exception);
                record.countFailed(project);
            }
        }
        var windowUntil = new HashMap<UUID, Instant>();
        var pausedSince = night.minusDays(PAUSE_NIGHTS).atStartOfDay(ZoneOffset.UTC).toInstant();
        Set<UUID> paused = new HashSet<>();
        // A deleted project keeps its window vectors until ClickHouse drops them: run only projects that exist.
        Set<UUID> existing = new HashSet<>();
        found.stream().collect(Collectors.groupingBy(Candidate::workspaceId,
                Collectors.mapping(Candidate::projectId, Collectors.toSet())))
                .forEach((workspaceId, ids) -> template.inTransaction(READ_ONLY, handle -> {
                    handle.attach(TopicDAO.class).findStates(workspaceId, ids)
                            .forEach(state -> windowUntil.put(state.projectId(), state.windowUntil()));
                    paused.addAll(handle.attach(TopicRunDAO.class)
                            .findPausedProjectIds(workspaceId, ids, PAUSE_FAILURES, pausedSince));
                    existing.addAll(handle.attach(TopicDAO.class).findExistingProjectIds(workspaceId, ids));
                    return null;
                }));
        for (var candidate : found) {
            if (!existing.contains(candidate.projectId())) {
                skipped.add(new Skipped(candidate, TopicNightProject.REASON_DELETED));
            } else if (paused.contains(candidate.projectId())) {
                skipped.add(new Skipped(candidate, TopicNightProject.REASON_PAUSED));
            } else {
                candidates.add(candidate);
            }
        }
        candidates.sort(queueOrder(windowUntil));
        record.skipped(skipped);
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
     * first the next night; among equals, the fewest vectors first. A project whose state row is gone sorts first.
     */
    static Comparator<Candidate> queueOrder(@NonNull Map<UUID, Instant> windowUntil) {
        return Comparator.<Candidate, Instant>comparing(candidate -> windowUntil.get(candidate.projectId()),
                Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparingLong(Candidate::vectors)
                .thenComparing(Candidate::projectId);
    }

    /**
     * Fails the nights left {@code running} past the night's lease (a pod that died), and deletes the nights and their
     * project rows past {@code topics.build.runsRetention}. A failure is logged, and the night goes on.
     */
    private void nightsHousekeeping(Instant start) {
        try {
            var before = LocalDate.ofInstant(start.minus(config.getBuild().getRunsRetention().toJavaDuration()),
                    ZoneOffset.UTC);
            template.inTransaction(WRITE, handle -> {
                var nights = handle.attach(TopicNightDAO.class);
                // This night started after start.minus(lease()): only a night whose lock has expired is failed.
                int failed = nights.failAbandoned(start.minus(lease()), Instant.now());
                int deleted = nights.deleteNightsBefore(before);
                int deletedProjects = nights.deleteProjectsBefore(before);
                if (failed > 0 || deleted > 0 || deletedProjects > 0) {
                    log.info("Topics night housekeeping, abandoned nights failed '{}', old nights deleted '{}', old "
                            + "night projects deleted '{}'", failed, deleted, deletedProjects);
                }
                return null;
            });
        } catch (RuntimeException exception) {
            log.warn("Topics night housekeeping of the night records failed", exception);
        }
    }

    /**
     * The night's record in {@code topic_nights} and {@code topic_night_projects}, written as the night goes: a row per
     * decision, which the state gauges publish. Its start must succeed, or the night does not run. A later write that fails is logged and the night goes on: the state gauges then show it, a missing
     * end as a night left running, which the next night's housekeeping fails. The first failed write of a night is a
     * WARN with its error, the others DEBUG, and the night's end WARNs how many failed.
     */
    private final class NightRecord {

        private final LocalDate night;
        private final Instant startedAt;
        private int writeFailures;

        NightRecord(LocalDate night, Instant startedAt) {
            this.night = night;
            this.startedAt = startedAt;
        }

        /** Replaces an earlier run of the same night, its project rows included. Throws when it cannot. */
        void start() {
            template.inTransaction(WRITE, handle -> {
                var dao = handle.attach(TopicNightDAO.class);
                dao.start(night, startedAt);
                dao.deleteProjects(night);
                return null;
            });
        }

        void countFailed(TopicDAO.ProjectKey project) {
            write("count failure", dao -> dao.insertProjects(List.of(TopicNightProject.builder()
                    .id(idGenerator.generateId())
                    .night(night)
                    .workspaceId(project.workspaceId())
                    .projectId(project.projectId())
                    .decision(TopicNightProject.COUNT_FAILED)
                    .build())));
        }

        /** {@code reason} is null for a decision other than skip, {@code runId} when no run started. */
        void decide(Candidate candidate, String decision, String reason, UUID runId) {
            write("project", dao -> dao.insertProjects(List.of(row(candidate, decision, reason, runId))));
        }

        /** The projects the night listed and skipped, in one write. */
        void skipped(List<Skipped> skipped) {
            if (skipped.isEmpty()) {
                return;
            }
            var rows = new ArrayList<TopicNightProject>(skipped.size());
            for (var skip : skipped) {
                rows.add(row(skip.candidate(), TopicNightProject.SKIP, skip.reason(), null));
            }
            write("skipped projects", dao -> dao.insertProjects(rows));
        }

        void end(TopicNight.Outcome outcome) {
            write("end", dao -> dao.end(new TopicNight(night, startedAt, Instant.now(), outcome)));
            if (writeFailures > 0) {
                log.warn("Topics night '{}' could not write '{}' parts of its record", night, writeFailures);
            }
        }

        private TopicNightProject row(Candidate candidate, String decision, String reason, UUID runId) {
            return TopicNightProject.builder()
                    .id(idGenerator.generateId())
                    .night(night)
                    .workspaceId(candidate.workspaceId())
                    .projectId(candidate.projectId())
                    .decision(decision)
                    .reason(reason)
                    .runId(runId)
                    .build();
        }

        private void write(String what, Consumer<TopicNightDAO> action) {
            try {
                template.inTransaction(WRITE, handle -> {
                    action.accept(handle.attach(TopicNightDAO.class));
                    return null;
                });
            } catch (RuntimeException exception) {
                if (writeFailures++ == 0) {
                    log.warn("Topics night '{}' could not write its record, '{}'", night, what, exception);
                } else {
                    log.debug("Topics night '{}' could not write its record, '{}'", night, what, exception);
                }
            }
        }
    }
}

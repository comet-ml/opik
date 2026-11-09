package com.comet.opik.domain.topics;

import com.comet.opik.api.error.ConflictException;
import com.comet.opik.domain.IdGenerator;
import com.comet.opik.domain.topics.TopicsRunException.Code;
import com.comet.opik.infrastructure.TopicsConfig;
import com.comet.opik.infrastructure.lock.LockService;
import com.comet.opik.utils.JsonUtils;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
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
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;
import static com.comet.opik.infrastructure.lock.LockService.Lock;

/**
 * One night of the topics job: picks the projects to build and runs them one at a time.
 *
 * <p>A project is built when its workspace is opted in to topics (and ai-labels) and its window (the {@code windowDays} whole UTC days
 * before the night) holds at least {@code minVectors} traces with a vector. Stage 2a builds only a project without a
 * list; one with a saved run is skipped until the nightly update exists. Projects run stalest first: one without a
 * list, then the oldest {@code window_until}, and among equals the smallest. None starts after {@code budget}, and
 * those left run the next night. A project whose last {@value #PAUSE_FAILURES} runs failed is paused for
 * {@value #PAUSE_NIGHTS} nights; a run by hand ignores the pause. Each run gets its own thread and is stopped
 * (interrupted) and failed with {@code timeout} after {@code projectTimeout}. During the day, the first-build job
 * ({@link #runFirstBuilds}) builds a project's first list early, through the run by hand.
 *
 * <p>Each night writes its record as it goes: its {@code topic_nights} row when it starts and ends, and one
 * {@code topic_night_projects} row per project as it decides it. The state gauges ({@link TopicsNightlyGauges}) read it.
 */
@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class TopicsNightlyService {

    /** Covers the housekeeping and the count reads before the first project, and the save after the last. */
    private static final Duration LEASE_SLACK = Duration.ofMinutes(5);

    /** The night's lock: one night at a time across the cluster, scheduled or started by hand. */
    public static final Lock NIGHT_LOCK = new Lock("topics:nightly:lock");

    /** One run started by hand at a time across the cluster: each holds a whole window in memory. */
    static final Lock BY_HAND_LOCK = new Lock("topics:by_hand");

    /** The first-build job's lock, held for one of its runs and released when it ends, so two runs never overlap. */
    public static final Lock FIRST_BUILD_LOCK = new Lock("topics:first_build:leader");

    /** The first-build job leaves out a project with a run started this recently: a failed one waits for the night. */
    static final Duration FIRST_BUILD_RUN_GAP = Duration.ofHours(24);

    /** How long a night started by hand waits to learn whether it got the night lock. */
    private static final Duration LOCK_WAIT = Duration.ofSeconds(10);

    /** ClickHouse {@code max_execution_time} of one window count: a workspace's, or one project's by hand. */
    private static final long COUNT_SECONDS = 60;

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
    private final @NonNull TopicsMetrics metrics;
    private final @NonNull LockService lockService;
    private final @NonNull IdGenerator idGenerator;

    /** What one night did, by project. */
    @Builder(toBuilder = true)
    public record NightResult(int saved, int failed, int skipped, int notReached) {
    }

    record Candidate(@NonNull String workspaceId, @NonNull UUID projectId, long vectors) {
    }

    /** A run started by hand; {@code saved} completes once the run has ended and released its locks. */
    private record ByHandRun(@NonNull UUID runId, @NonNull CompletableFuture<Boolean> saved) {
    }

    /** A project the night listed and skipped, with why. */
    private record Skipped(@NonNull Candidate candidate, @NonNull String reason) {
    }

    private enum Outcome {
        SAVED,
        FAILED,
        SKIPPED
    }

    /**
     * How long the night's lock must last: a project may start just before the budget ends and run its whole timeout.
     */
    public Duration lease() {
        return config.getNightly().getBudget().toJavaDuration().plus(runLimit()).plus(LEASE_SLACK);
    }

    /** How long the first-build job's lock must last, as the night's: its budget, then one whole project timeout. */
    public Duration firstBuildLease() {
        return config.getFirstBuild().getBudget().toJavaDuration().plus(runLimit()).plus(LEASE_SLACK);
    }

    /**
     * The longest one run holds its locks: its timeout, then the trace_topics insert of a run that saved just before
     * it ({@link #stop} waits for that insert).
     */
    private Duration runLimit() {
        return config.getNightly().getProjectTimeout().toJavaDuration().plus(TopicsRebuildService.INSERT_BOUND);
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
        var nightly = config.getNightly();
        housekeeping(began);
        var candidates = new ArrayList<Candidate>();
        int skipped = eligible(night, candidates, record);
        log.info("Topics night '{}' started, projects to build '{}', skipped '{}'", night, candidates.size(),
                skipped);

        var budgetEnd = began.plus(nightly.getBudget().toJavaDuration());
        int saved = 0;
        int failed = 0;
        int notReached = 0;
        var executor = Executors.newThreadPerTaskExecutor(
                Thread.ofPlatform().name("topics-nightly-", 0).daemon(true).factory());
        try {
            for (var candidate : candidates) {
                if (Thread.currentThread().isInterrupted() || !Instant.now().isBefore(budgetEnd)) {
                    notReached++;
                    record.decide(candidate, TopicsMetrics.DECISION_NOT_REACHED, null, null);
                    continue;
                }
                if (!lockProject(candidate.projectId())) {
                    log.info("Topics night skipped project '{}', a run started by hand holds it",
                            candidate.projectId());
                    skipped++;
                    record.decide(candidate, TopicsMetrics.DECISION_SKIP, TopicsMetrics.REASON_LOCKED, null);
                    continue;
                }
                try {
                    switch (runOne(executor, candidate, night, record)) {
                        case SAVED -> saved++;
                        case FAILED -> failed++;
                        case SKIPPED -> skipped++;
                    }
                } finally {
                    unlockProject(candidate.projectId());
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
     * Starts the project's first build now, outside the night, and returns its run id. The run goes on in the background
     * with the same timeout, and its outcome is its {@code topic_runs} row. The window is the one of {@code night}: it
     * ends at that day's start (UTC). The night's rules apply: the workspace must be opted in to topics, the window must
     * hold {@code minVectors} vectors of the project, and only one run by hand goes at a time across the cluster.
     *
     * @throws ConflictException when the nightly job is off, the workspace is not opted in, the window is too small, a
     *                           run of the project or another run by hand is in progress, or the project already has a
     *                           topic list (a list is built once; the night updates it)
     */
    public UUID runNow(@NonNull String workspaceId, @NonNull UUID projectId, @NonNull LocalDate night) {
        return startByHand(workspaceId, projectId, night).runId();
    }

    private ByHandRun startByHand(String workspaceId, UUID projectId, LocalDate night) {
        checkByHand(workspaceId);
        long vectors = windowVectors(workspaceId, projectId, night);
        if (vectors < config.getNightly().getMinVectors()) {
            throw new ConflictException("Project '%s' has '%d' vectors in the window of night '%s', the minimum is '%d'"
                    .formatted(projectId, vectors, night, config.getNightly().getMinVectors()));
        }
        if (!lock(BY_HAND_LOCK, projectLease())) {
            throw new ConflictException("Another topics run started by hand is in progress");
        }
        if (!lockProject(projectId)) {
            unlock(BY_HAND_LOCK);
            throw new ConflictException("A topics run of project '%s' is in progress".formatted(projectId));
        }
        try {
            if (hasList(workspaceId, projectId)) {
                throw new ConflictException(
                        "Project '%s' already has a topic list; the nightly job updates it".formatted(projectId));
            }
            UUID runId;
            try {
                runId = rebuildService.startRun(workspaceId, projectId, TopicRun.Mode.FIRST_BUILD);
            } catch (RuntimeException exception) {
                metrics.run(workspaceId, TopicRun.Status.FAILED, TopicsMetrics.ERROR_START_FAILED);
                throw exception;
            }
            log.info("Topics run '{}' started by hand, workspace '{}', project '{}', night '{}'", runId, workspaceId,
                    projectId, night);
            var candidate = new Candidate(workspaceId, projectId, vectors);
            var saved = new CompletableFuture<Boolean>();
            Thread.ofPlatform().name("topics-by-hand-" + projectId).daemon(true).start(() -> {
                var executor = Executors.newThreadPerTaskExecutor(
                        Thread.ofPlatform().name("topics-by-hand-run-", 0).daemon(true).factory());
                boolean result = false;
                try {
                    result = run(executor, runId, candidate, night);
                } finally {
                    executor.shutdownNow();
                    unlockProject(projectId);
                    unlock(BY_HAND_LOCK);
                    // After the locks, so a caller that waits for it can start the next run at once.
                    saved.complete(result);
                }
            });
            return new ByHandRun(runId, saved);
        } catch (RuntimeException exception) {
            unlockProject(projectId);
            unlock(BY_HAND_LOCK);
            throw exception;
        }
    }

    /**
     * The first-build job's run: builds, one at a time and in {@link #firstBuildCandidates} order, the list of each
     * project that has none yet, for tomorrow's night (UTC), so its window holds today's traces so far. Each goes
     * through the run by hand ({@link #runNow}) under its usual locks, and is waited for. Skipped while the night holds
     * its lock. No project starts once the night takes the lock or {@code topics.firstBuild.budget} is used, nor after
     * one that cannot start: the next run reads the projects again. A failed run is left to the night: the project is
     * out of this job for {@link #FIRST_BUILD_RUN_GAP}.
     */
    public void runFirstBuilds(@NonNull Instant start) {
        if (nightRunning()) {
            log.info("Topics first builds skipped, the night holds lock '{}'", NIGHT_LOCK.key());
            return;
        }
        var began = Instant.now();
        var night = LocalDate.ofInstant(start, ZoneOffset.UTC).plusDays(1);
        var candidates = firstBuildCandidates(night, began);
        log.info("Topics first builds started, night '{}', projects to build '{}'", night, candidates.size());
        var budgetEnd = began.plus(config.getFirstBuild().getBudget().toJavaDuration());
        int saved = 0;
        int failed = 0;
        for (var candidate : candidates) {
            if (Thread.currentThread().isInterrupted() || !Instant.now().isBefore(budgetEnd) || nightRunning()) {
                break;
            }
            try {
                if (startByHand(candidate.workspaceId(), candidate.projectId(), night).saved().join()) {
                    saved++;
                } else {
                    failed++;
                }
            } catch (ConflictException exception) {
                log.info("Topics first builds stopped at project '{}': {}", candidate.projectId(),
                        exception.getMessage());
                break;
            } catch (RuntimeException exception) {
                log.warn("Topics first builds stopped, project '{}' could not start", candidate.projectId(),
                        exception);
                break;
            }
        }
        log.info("Topics first builds finished, night '{}', saved '{}', failed '{}', not started '{}', seconds '{}'",
                night, saved, failed, candidates.size() - saved - failed,
                Duration.between(began, Instant.now()).toSeconds());
    }

    /**
     * The first-build job's projects for {@code night}, the most vectors first: those of the opted-in workspaces whose
     * window holds at least {@code minVectors} vectors, that exist, have no list, are not paused (as the night counts
     * it) and had no run started in the {@link #FIRST_BUILD_RUN_GAP} before {@code now}. A workspace whose count read
     * fails is left for the next run.
     */
    List<Candidate> firstBuildCandidates(@NonNull LocalDate night, @NonNull Instant now) {
        var nightly = config.getNightly();
        var to = night.atStartOfDay(ZoneOffset.UTC).toInstant();
        var from = night.minusDays(nightly.getWindowDays()).atStartOfDay(ZoneOffset.UTC).toInstant();
        var found = new ArrayList<Candidate>();
        for (var workspaceId : workspaceService.eligibleWorkspaceIds()) {
            try {
                windowDAO.countByProject(workspaceId, null, from, to, COUNT_SECONDS).collectList().block().stream()
                        .filter(count -> count.traces() >= nightly.getMinVectors())
                        .forEach(count -> found.add(new Candidate(workspaceId, count.projectId(), count.traces())));
            } catch (RuntimeException exception) {
                log.warn("Topics first builds count read failed, workspace '{}'; it is read again the next run",
                        workspaceId, exception);
            }
        }
        if (found.isEmpty()) {
            return List.of();
        }
        var pausedSince = night.minusDays(PAUSE_NIGHTS).atStartOfDay(ZoneOffset.UTC).toInstant();
        return template.inTransaction(READ_ONLY, handle -> {
            var topicDAO = handle.attach(TopicDAO.class);
            var runDAO = handle.attach(TopicRunDAO.class);
            Set<UUID> leftOut = new HashSet<>();
            Set<UUID> existing = new HashSet<>();
            found.stream().collect(Collectors.groupingBy(Candidate::workspaceId,
                    Collectors.mapping(Candidate::projectId, Collectors.toSet())))
                    .forEach((workspaceId, ids) -> {
                        topicDAO.findStates(workspaceId, ids).forEach(state -> leftOut.add(state.projectId()));
                        leftOut.addAll(runDAO.findStartedSince(workspaceId, ids, now.minus(FIRST_BUILD_RUN_GAP)));
                        leftOut.addAll(runDAO.findPausedProjectIds(workspaceId, ids, PAUSE_FAILURES, pausedSince));
                        existing.addAll(topicDAO.findExistingProjectIds(workspaceId, ids));
                    });
            return found.stream()
                    .filter(candidate -> existing.contains(candidate.projectId())
                            && !leftOut.contains(candidate.projectId()))
                    .sorted(Comparator.comparingLong(Candidate::vectors).reversed()
                            .thenComparing(Candidate::projectId))
                    .toList();
        });
    }

    /** Whether a night holds its lock now, read without taking it. Unknown (Redis failed) counts as held. */
    private boolean nightRunning() {
        try {
            return !Boolean.FALSE.equals(lockService.isLocked(NIGHT_LOCK).block());
        } catch (RuntimeException exception) {
            log.warn("Topics lock unavailable, lock '{}'", NIGHT_LOCK.key(), exception);
            return true;
        }
    }

    /**
     * Runs the whole night {@code night} now, in the background, as the scheduled job would, under the same night lock;
     * the lock is released when the night ends. False when a night is in progress (this one or the scheduled one).
     *
     * @throws ConflictException when the nightly job is off
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
                    runNight(night.atStartOfDay(ZoneOffset.UTC).toInstant());
                }).subscribeOn(Schedulers.boundedElastic()),
                Mono.fromRunnable(() -> started.complete(false)),
                lease(),
                Duration.ZERO,
                false)
                .subscribe(
                        __ -> {
                        },
                        error -> {
                            started.complete(false);
                            log.error("Topics night '{}' started by hand failed", night, error);
                        });
        try {
            return started.get(LOCK_WAIT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException | TimeoutException exception) {
            return false;
        }
    }

    /** The night's rules a run by hand also follows, checked before any lock is taken. */
    private void checkByHand(String workspaceId) {
        if (!config.getNightly().isEnabled()) {
            throw new ConflictException("The topics nightly job is off: topics.nightly.enabled is false");
        }
        if (!workspaceService.isEligible(workspaceId)) {
            throw new ConflictException(("Workspace '%s' is not opted in to topics: it needs to be in "
                    + "topics.enabledWorkspaces and enabled in ai_label_workspaces").formatted(workspaceId));
        }
    }

    /**
     * The project's traces with a vector in the window of {@code night}, read within the workspace: 0 for a project of
     * another workspace. The request waits for it, so it gets the night's own count limit, not the project timeout.
     */
    private long windowVectors(String workspaceId, UUID projectId, LocalDate night) {
        var nightly = config.getNightly();
        var to = night.atStartOfDay(ZoneOffset.UTC).toInstant();
        var from = night.minusDays(nightly.getWindowDays()).atStartOfDay(ZoneOffset.UTC).toInstant();
        var counts = windowDAO.countByProject(workspaceId, projectId, from, to, COUNT_SECONDS)
                .collectList().block();
        return counts.stream().filter(count -> count.projectId().equals(projectId))
                .mapToLong(TopicWindowDAO.ProjectCount::traces).findFirst().orElse(0);
    }

    /** Read under the project's lock, so a list built meanwhile by the night or by hand is seen. */
    private boolean hasList(String workspaceId, UUID projectId) {
        return !template.inTransaction(READ_ONLY,
                handle -> handle.attach(TopicDAO.class).findStates(workspaceId, Set.of(projectId))).isEmpty();
    }

    /**
     * Builds the project under its lock. A run that cannot start (its read or its {@code topic_runs} insert fails) has
     * no row to fail, so it is counted here, as {@code start_failed}.
     */
    private Outcome runOne(ExecutorService executor, Candidate candidate, LocalDate night, NightRecord record) {
        UUID runId = null;
        try {
            // Read again under the project's lock: a run by hand may have built it since the night listed it.
            if (hasList(candidate.workspaceId(), candidate.projectId())) {
                log.info("Topics night skipped project '{}', it got a topic list since the night listed it",
                        candidate.projectId());
                record.decide(candidate, TopicsMetrics.DECISION_SKIP, TopicsMetrics.REASON_ALREADY_SAVED, null);
                return Outcome.SKIPPED;
            }
            runId = rebuildService.startRun(candidate.workspaceId(), candidate.projectId(),
                    TopicRun.Mode.FIRST_BUILD);
        } catch (RuntimeException exception) {
            log.error("Topics run could not start, project '{}'", candidate.projectId(), exception);
        }
        record.decide(candidate, TopicsMetrics.DECISION_FIRST_BUILD, null, runId);
        if (runId == null) {
            metrics.run(candidate.workspaceId(), TopicRun.Status.FAILED, TopicsMetrics.ERROR_START_FAILED);
            return Outcome.FAILED;
        }
        log.info("Topics run '{}' started, workspace '{}', project '{}', vectors '{}'", runId,
                candidate.workspaceId(), candidate.projectId(), candidate.vectors());
        return run(executor, runId, candidate, night) ? Outcome.SAVED : Outcome.FAILED;
    }

    /** Runs the build on its own thread, stopped after the project timeout. True when it was saved. */
    private boolean run(ExecutorService executor, UUID runId, Candidate candidate, LocalDate night) {
        var future = executor.submit(() -> rebuildService.firstBuild(runId, candidate.workspaceId(),
                candidate.projectId(), night));
        var timeout = config.getNightly().getProjectTimeout().toJavaDuration();
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS).status() == TopicRun.Status.SAVED;
        } catch (TimeoutException exception) {
            return stop(runId, candidate, future, Code.TIMEOUT, "the run outlasted topics.nightly.projectTimeout '%s'"
                    .formatted(timeout));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return stop(runId, candidate, future, Code.TIMEOUT, "the nightly job was interrupted");
        } catch (ExecutionException exception) {
            // firstBuild records its own failures; this is a bug in it.
            log.error("Topics run '{}' threw, project '{}'", runId, candidate.projectId(), exception.getCause());
            return stop(runId, candidate, future, Code.INTERNAL, String.valueOf(exception.getCause()));
        }
    }

    /**
     * Fails the run first, so a save racing the interrupt finds it ended and rolls back. True when the run had saved
     * already: the stop came after its MySQL commit, while its trace_topics insert went on. Such a run counts as saved,
     * and its insert is waited for (it ends within {@link TopicsRebuildService#INSERT_BOUND}), so the project's locks
     * are released only after it.
     */
    private boolean stop(UUID runId, Candidate candidate, Future<?> future, Code code, String message) {
        boolean saved = false;
        try {
            if (rebuildService.fail(runId, candidate.workspaceId(), code, message, null)) {
                log.warn("Topics run '{}' failed, code '{}': {}", runId, code.getValue(), message);
            } else if (savedRun(runId, candidate.workspaceId())) {
                saved = true;
                log.warn("Topics run '{}' saved, project '{}', but its trace_topics insert outlasted the stop: {}",
                        runId, candidate.projectId(), message);
            }
        } catch (RuntimeException exception) {
            log.error("Topics run '{}' could not be marked failed", runId, exception);
        }
        if (saved) {
            awaitInsert(future);
        }
        future.cancel(true);
        return saved;
    }

    /** Waits for a saved run's insert. An interrupted job does not wait: it is shutting down. */
    private void awaitInsert(Future<?> future) {
        try {
            future.get(TopicsRebuildService.INSERT_BOUND.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException exception) {
            // The insert logs its own failure; the run stays saved.
        }
    }

    /** Whether the run's row says saved. A run that failed on its own logged its failure already. */
    private boolean savedRun(UUID runId, String workspaceId) {
        var run = template.inTransaction(READ_ONLY,
                handle -> handle.attach(TopicRunDAO.class).findById(workspaceId, runId));
        return run != null && run.status() == TopicRun.Status.SAVED;
    }

    /**
     * The project's lock, held for one run, so a run started by hand and the night never build it at once. Redis down:
     * not taken.
     */
    private boolean lockProject(UUID projectId) {
        return lock(projectLock(projectId), projectLease());
    }

    private void unlockProject(UUID projectId) {
        unlock(projectLock(projectId));
    }

    private boolean lock(Lock lock, Duration lease) {
        try {
            return Boolean.TRUE.equals(lockService.lockUsingToken(lock, lease).block());
        } catch (RuntimeException exception) {
            log.warn("Topics lock unavailable, lock '{}'", lock.key(), exception);
            return false;
        }
    }

    private void unlock(Lock lock) {
        try {
            lockService.unlockUsingToken(lock).block();
        } catch (RuntimeException exception) {
            log.warn("Topics lock release failed, lock '{}'; it expires with its lease", lock.key(), exception);
        }
    }

    private Duration projectLease() {
        return runLimit().plus(LEASE_SLACK);
    }

    static Lock projectLock(UUID projectId) {
        return new Lock("topics:project:" + projectId);
    }

    /**
     * Fills {@code candidates} with the projects to build, in {@link #queueOrder} order, and returns how many were
     * skipped: under {@code minVectors}, deleted, with a list, or paused. A workspace whose count read fails is left for
     * the next night. A read that fails otherwise throws, and fails the night.
     */
    private int eligible(LocalDate night, List<Candidate> candidates, NightRecord record) {
        var nightly = config.getNightly();
        var to = night.atStartOfDay(ZoneOffset.UTC).toInstant();
        var from = night.minusDays(nightly.getWindowDays()).atStartOfDay(ZoneOffset.UTC).toInstant();
        var workspaceIds = workspaceService.eligibleWorkspaceIds();
        record.workspaces(workspaceIds.size());
        var skipped = new ArrayList<Skipped>();
        var found = new ArrayList<Candidate>();
        for (var workspaceId : workspaceIds) {
            try {
                var counts = windowDAO.countByProject(workspaceId, null, from, to, COUNT_SECONDS)
                        .collectList().block();
                for (var count : counts) {
                    var candidate = new Candidate(workspaceId, count.projectId(), count.traces());
                    if (count.traces() >= nightly.getMinVectors()) {
                        found.add(candidate);
                    } else {
                        skipped.add(new Skipped(candidate, TopicsMetrics.REASON_SMALL));
                    }
                }
            } catch (RuntimeException exception) {
                log.warn("Topics night count read failed, workspace '{}'; it runs the next night", workspaceId,
                        exception);
                record.countFailed(workspaceId, exception);
            }
        }
        Set<UUID> built = new HashSet<>();
        var windowUntil = new HashMap<UUID, Instant>();
        var pausedSince = night.minusDays(PAUSE_NIGHTS).atStartOfDay(ZoneOffset.UTC).toInstant();
        Set<UUID> paused = new HashSet<>();
        // A deleted project keeps its window vectors until ClickHouse drops them: build only projects that exist.
        Set<UUID> existing = new HashSet<>();
        found.stream().collect(Collectors.groupingBy(Candidate::workspaceId,
                Collectors.mapping(Candidate::projectId, Collectors.toSet())))
                .forEach((workspaceId, ids) -> template.inTransaction(READ_ONLY, handle -> {
                    handle.attach(TopicDAO.class).findStates(workspaceId, ids).forEach(state -> {
                        built.add(state.projectId());
                        windowUntil.put(state.projectId(), state.windowUntil());
                    });
                    paused.addAll(handle.attach(TopicRunDAO.class)
                            .findPausedProjectIds(workspaceId, ids, PAUSE_FAILURES, pausedSince));
                    existing.addAll(handle.attach(TopicDAO.class).findExistingProjectIds(workspaceId, ids));
                    return null;
                }));
        for (var candidate : found) {
            if (!existing.contains(candidate.projectId())) {
                skipped.add(new Skipped(candidate, TopicsMetrics.REASON_DELETED));
            } else if (built.contains(candidate.projectId())) {
                skipped.add(new Skipped(candidate, TopicsMetrics.REASON_ALREADY_SAVED));
            } else if (paused.contains(candidate.projectId())) {
                skipped.add(new Skipped(candidate, TopicsMetrics.REASON_PAUSED));
            } else {
                candidates.add(candidate);
            }
        }
        candidates.sort(queueOrder(windowUntil));
        record.skipped(skipped);
        return skipped.size();
    }

    /**
     * The night's queue, stalest first: a project without a list ({@code windowUntil} has none, or a null), then the
     * oldest {@code window_until}, so a project with a list that a night did not reach goes first the next night; among
     * equals, the fewest vectors first.
     */
    static Comparator<Candidate> queueOrder(@NonNull Map<UUID, Instant> windowUntil) {
        return Comparator.<Candidate, Instant>comparing(candidate -> windowUntil.get(candidate.projectId()),
                Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparingLong(Candidate::vectors)
                .thenComparing(Candidate::projectId);
    }

    /**
     * Fails runs left {@code running} by a pod that died, and deletes runs and their counts past
     * {@code runsRetention}. A run counts as left only once it is older than a run can last ({@code projectTimeout}
     * plus slack): this night holds the night lock, but a run started by hand holds only its project's lock. Each run
     * failed so is counted, as {@code abandoned}, once the transaction commits: a run whose own failure mark threw was
     * not counted then. Then does the same for nights: fails those left {@code running} past the night's lease, and
     * deletes those past the retention.
     */
    private void housekeeping(Instant start) {
        try {
            var retention = config.getNightly().getRunsRetention().toJavaDuration();
            var abandoned = template.inTransaction(WRITE, handle -> {
                var runs = handle.attach(TopicRunDAO.class);
                // A run younger than its timeout may be a live run started by hand: only the project lock guards it.
                var startedBefore = start.minus(projectLease());
                var workspaceIds = runs.findAbandonedWorkspaceIds(startedBefore);
                int failed = runs.failAbandoned(startedBefore, Instant.now());
                // The counts first, by their runs' start: a run saved after the cut would leave its counts behind.
                int deletedCounts = runs.deleteCountsOfRunsStartedBefore(start.minus(retention));
                int deleted = runs.deleteStartedBefore(start.minus(retention));
                if (failed > 0 || deleted > 0 || deletedCounts > 0) {
                    log.info("Topics night housekeeping, abandoned runs failed '{}', old runs deleted '{}', old run "
                            + "counts deleted '{}'", failed, deleted, deletedCounts);
                }
                return workspaceIds;
            });
            abandoned.forEach(workspaceId -> metrics.run(workspaceId, TopicRun.Status.FAILED,
                    TopicsMetrics.ERROR_ABANDONED));
        } catch (RuntimeException exception) {
            log.warn("Topics night housekeeping failed", exception);
        }
        try {
            var before = LocalDate.ofInstant(start.minus(config.getNightly().getRunsRetention().toJavaDuration()),
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
     * The night's record in {@code topic_nights} and {@code topic_night_projects}, written as the night goes. Each
     * decision is counted in {@code topics.nightly.projects} and written as a row. Its start must succeed, or the night
     * does not run. A later write that fails is logged and the night goes on: the state gauges then show it, a missing
     * end as a night left running, which the next night's housekeeping fails. The first failed write of a night is a
     * WARN with its error, the others DEBUG, and the night's end WARNs how many failed.
     */
    private final class NightRecord {

        private final LocalDate night;
        private final Instant startedAt;
        private final Map<String, Integer> projects = new TreeMap<>();
        private int eligibleWorkspaces;
        private int countFailures;
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

        void workspaces(int count) {
            eligibleWorkspaces = count;
        }

        void countFailed(String workspaceId, RuntimeException error) {
            countFailures++;
            metrics.countFailure(workspaceId, error);
            write("count failure", dao -> dao.insertProjects(List.of(TopicNightProject.builder()
                    .id(idGenerator.generateId())
                    .night(night)
                    .workspaceId(workspaceId)
                    .decision(TopicNightProject.COUNT_FAILED)
                    .build())));
        }

        /** {@code reason} is null for a decision other than skip, {@code runId} when no run started. */
        void decide(Candidate candidate, String decision, String reason, UUID runId) {
            count(candidate, decision, reason);
            write("project", dao -> dao.insertProjects(List.of(row(candidate, decision, reason, runId))));
        }

        /** The projects the night listed and skipped, in one write. */
        void skipped(List<Skipped> skipped) {
            if (skipped.isEmpty()) {
                return;
            }
            var rows = new ArrayList<TopicNightProject>(skipped.size());
            for (var skip : skipped) {
                count(skip.candidate(), TopicsMetrics.DECISION_SKIP, skip.reason());
                rows.add(row(skip.candidate(), TopicsMetrics.DECISION_SKIP, skip.reason(), null));
            }
            write("skipped projects", dao -> dao.insertProjects(rows));
        }

        void end(TopicNight.Outcome outcome) {
            var counts = JsonUtils.createObjectNode();
            projects.forEach(counts::put);
            write("end", dao -> dao.end(TopicNight.builder()
                    .night(night)
                    .startedAt(startedAt)
                    .finishedAt(Instant.now())
                    .outcome(outcome)
                    .eligibleWorkspaces(eligibleWorkspaces)
                    .countFailures(countFailures)
                    .projects(counts.toString())
                    .build()));
            if (writeFailures > 0) {
                log.warn("Topics night '{}' could not write '{}' parts of its record", night, writeFailures);
            }
        }

        private void count(Candidate candidate, String decision, String reason) {
            projects.merge(decision, 1, Integer::sum);
            metrics.project(candidate.workspaceId(), decision, reason);
        }

        private TopicNightProject row(Candidate candidate, String decision, String reason, UUID runId) {
            return TopicNightProject.builder()
                    .id(idGenerator.generateId())
                    .night(night)
                    .workspaceId(candidate.workspaceId())
                    .projectId(candidate.projectId())
                    .vectors(candidate.vectors())
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

package com.comet.opik.domain.topics;

import com.comet.opik.api.error.ConflictException;
import com.comet.opik.infrastructure.TopicsConfig;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.ServiceUnavailableException;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;
import static com.comet.opik.infrastructure.lock.LockService.Lock;

/**
 * Builds the first topic list of each project that has none: the first-build job's run, and a run started by hand.
 *
 * <p>A job run takes the projects of the opted-in workspaces (topics and ai-labels) that have no list, had a trace
 * written in the window, hold at least {@code topics.build.minVectors} traces with a vector in it, whose vectors
 * backfill has drained ({@link TopicsBackfillGate}), and whose last failed first build is old enough: a project waits 1
 * hour after its first failure, 6 hours after its second and a day after each later one. It builds them one at a time,
 * the most vectors first, each under its project's lock, until {@code topics.firstBuild.budget} is used. The window ends
 * at tomorrow's start (UTC), so it holds today's traces so far. A project that cannot start is skipped, and the run goes
 * on.
 */
@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class TopicsFirstBuildService {

    /** The job's lock, held for one of its runs and released when it ends, so two runs never overlap. */
    public static final Lock LEADER_LOCK = new Lock("topics:first_build:leader");

    /** One run by hand at a time across the cluster: each holds a whole window in memory. */
    static final Lock BY_HAND_LOCK = new Lock("topics:by_hand");

    /** The wait after a project's first, second, and each later failed first build. */
    static final List<Duration> RETRY_DELAYS = List.of(Duration.ofHours(1), Duration.ofHours(6), Duration.ofDays(1));

    /** A project counts as waiting for its list once its first failed first build started this long ago. */
    static final Duration WAITING_AFTER = Duration.ofDays(1);

    private final @NonNull @Config("topics") TopicsConfig config;
    private final @NonNull TransactionTemplate template;
    private final @NonNull TopicWorkspaceService workspaceService;
    private final @NonNull TopicWindowDAO windowDAO;
    private final @NonNull TopicsRebuildService rebuildService;
    private final @NonNull TopicsProjectRunner runner;
    private final @NonNull TopicsMetrics metrics;
    private final @NonNull TopicsBackfillGate backfillGate;

    record Candidate(@NonNull String workspaceId, @NonNull UUID projectId, long vectors) {
    }

    /** The projects without a list whose gate is open, and per workspace the longest wait of the others. */
    private record Gated(@NonNull Map<String, List<UUID>> open, @NonNull Map<String, Duration> longestWaits) {
    }

    private enum Outcome {
        SAVED,
        FAILED,
        SKIPPED
    }

    /** How long the job's lock must last: its budget, then one whole run. */
    public Duration lease() {
        return config.getFirstBuild().getBudget().toJavaDuration().plus(runner.projectLease());
    }

    /** The wait after {@code failures} failed first builds of a project. */
    static Duration retryDelay(int failures) {
        return RETRY_DELAYS.get(Math.min(failures, RETRY_DELAYS.size()) - 1);
    }

    /**
     * The job's run: the runs' housekeeping and the delete of deleted projects' topic data, then the first builds of
     * {@link #candidates}, one at a time, until the budget is used. The window ends at the start of the day after
     * {@code start} (UTC).
     */
    public void run(@NonNull Instant start) {
        var began = Instant.now();
        runner.housekeeping(began);
        var workspaceIds = workspaceService.eligibleWorkspaceIds();
        runner.deleteOfMissingProjects(workspaceIds);
        var night = LocalDate.ofInstant(start, ZoneOffset.UTC).plusDays(1);
        var candidates = candidates(night, began, workspaceIds);
        log.info("Topics first builds started, window until '{}', projects to build '{}'", night, candidates.size());
        var budgetEnd = began.plus(config.getFirstBuild().getBudget().toJavaDuration());
        int saved = 0;
        int failed = 0;
        int skipped = 0;
        var executor = Executors.newThreadPerTaskExecutor(
                Thread.ofPlatform().name("topics-first-build-", 0).daemon(true).factory());
        try {
            for (var candidate : candidates) {
                if (Thread.currentThread().isInterrupted() || !Instant.now().isBefore(budgetEnd)) {
                    break;
                }
                switch (runOne(executor, candidate, night)) {
                    case SAVED -> saved++;
                    case FAILED -> failed++;
                    case SKIPPED -> skipped++;
                }
            }
        } finally {
            // Not close(): it waits for every task, and a run stopped by its timeout may ignore the interrupt.
            executor.shutdownNow();
        }
        log.info("Topics first builds finished, window until '{}', saved '{}', failed '{}', skipped '{}', not started "
                + "'{}', seconds '{}'", night, saved, failed, skipped, candidates.size() - saved - failed - skipped,
                Duration.between(began, Instant.now()).toSeconds());
    }

    /**
     * Builds the project under its lock. Skipped when another run holds the lock or the project got a list since it
     * was listed. A run that cannot start (the read or its {@code topic_runs} insert fails) has no row to fail, so it
     * is counted here, as {@code start_failed}.
     */
    private Outcome runOne(ExecutorService executor, Candidate candidate, LocalDate night) {
        var workspaceId = candidate.workspaceId();
        var projectId = candidate.projectId();
        if (!runner.lockProject(projectId)) {
            log.info("Topics first build skipped project '{}', another run holds its lock", projectId);
            return Outcome.SKIPPED;
        }
        try {
            UUID runId;
            try {
                if (runner.hasList(workspaceId, projectId)) {
                    log.info("Topics first build skipped project '{}', it got a list since it was listed", projectId);
                    return Outcome.SKIPPED;
                }
                runId = rebuildService.startRun(workspaceId, projectId, TopicRun.Mode.FIRST_BUILD);
            } catch (RuntimeException exception) {
                log.error("Topics run could not start, project '{}'", projectId, exception);
                metrics.run(workspaceId, TopicRun.Status.FAILED, TopicsMetrics.ERROR_START_FAILED);
                return Outcome.FAILED;
            }
            log.info("Topics run '{}' started, workspace '{}', project '{}', vectors '{}'", runId, workspaceId,
                    projectId, candidate.vectors());
            return runner.run(executor, runId, workspaceId, projectId, night) ? Outcome.SAVED : Outcome.FAILED;
        } finally {
            runner.unlockProject(projectId);
        }
    }

    /**
     * The projects to build for the window that ends at {@code night}'s start, the most vectors first. Reads MySQL
     * first, for the projects with no list and a trace written in the window, and the backfill gate; ClickHouse counts
     * only the projects whose gate is open, so a run with nothing to build reads no ClickHouse. A project whose count
     * read fails is left for the next run. Also publishes the gate's waits and the projects waiting for a list.
     */
    List<Candidate> candidates(@NonNull LocalDate night, @NonNull Instant now) {
        return candidates(night, now, workspaceService.eligibleWorkspaceIds());
    }

    private List<Candidate> candidates(LocalDate night, Instant now, List<String> workspaceIds) {
        var build = config.getBuild();
        var to = night.atStartOfDay(ZoneOffset.UTC).toInstant();
        var from = night.minusDays(build.getWindowDays()).atStartOfDay(ZoneOffset.UTC).toInstant();
        var listless = workspaceIds.isEmpty()
                ? Map.<String, List<UUID>>of()
                : template.inTransaction(READ_ONLY,
                        handle -> handle.attach(TopicDAO.class).findListlessProjects(workspaceIds, from)).stream()
                        .collect(Collectors.groupingBy(TopicDAO.ProjectKey::workspaceId, TreeMap::new,
                                Collectors.mapping(TopicDAO.ProjectKey::projectId, Collectors.toList())));
        var gated = drained(listless, now);
        var found = new ArrayList<Candidate>();
        // One count per project: the count's max_rows_to_read applies to one query, so a large project fails only
        // its own count, never its workspace's.
        gated.open().forEach((workspaceId, projectIds) -> projectIds.forEach(projectId -> {
            try {
                long traces = windowVectors(workspaceId, projectId, from, to);
                if (traces >= build.getMinVectors()) {
                    found.add(new Candidate(workspaceId, projectId, traces));
                }
            } catch (RuntimeException exception) {
                log.warn("Topics first builds count read failed, workspace '{}', project '{}'; it is read again the "
                        + "next run", workspaceId, projectId, exception);
            }
        }));
        return due(found, workspaceIds, gated.longestWaits(), now).stream()
                .sorted(Comparator.comparingLong(Candidate::vectors).reversed().thenComparing(Candidate::projectId))
                .toList();
    }

    /**
     * Of the projects without a list, per workspace, those whose backfill gate is open. Each project is counted by its
     * gate state; the longest wait of each workspace's waiting projects is kept for the gauge, and a workspace with
     * projects that wait past {@code backfillMaxWait} is logged once.
     */
    private Gated drained(Map<String, List<UUID>> listless, Instant now) {
        var gate = backfillGate.read(listless.keySet(), now);
        var open = new TreeMap<String, List<UUID>>();
        var longestWaits = new HashMap<String, Duration>();
        var overdue = new HashMap<String, Integer>();
        listless.forEach((workspaceId, projectIds) -> {
            for (var projectId : projectIds) {
                var state = gate.state(workspaceId, projectId);
                metrics.firstBuildGate(workspaceId, state);
                if (state.isOpen()) {
                    open.computeIfAbsent(workspaceId, key -> new ArrayList<>()).add(projectId);
                    continue;
                }
                longestWaits.merge(workspaceId, gate.waited(workspaceId, projectId),
                        (a, b) -> a.compareTo(b) >= 0 ? a : b);
                if (state == TopicsBackfillGate.State.TIMED_OUT_WAITING) {
                    overdue.merge(workspaceId, 1, Integer::sum);
                }
            }
        });
        overdue.forEach((workspaceId, projects) -> log.warn(
                "Topics first builds wait for the vectors backfill past topics.firstBuild.backfillMaxWait '{}' with "
                        + "under 99% of the traces embedded, workspace '{}', projects '{}', longest wait seconds '{}'",
                config.getFirstBuild().getBackfillMaxWait(), workspaceId, projects,
                longestWaits.get(workspaceId).toSeconds()));
        return new Gated(open, longestWaits);
    }

    /**
     * Of {@code found}, those whose last failed first build is older than its {@link #retryDelay}, or that have none.
     * Publishes, per workspace, the gate's longest wait and the projects whose first failure started more than
     * {@link #WAITING_AFTER} ago; every eligible workspace gets both, 0 when it has none, so a wait that ended is seen to
     * end.
     */
    private List<Candidate> due(List<Candidate> found, List<String> workspaceIds,
            Map<String, Duration> longestWaits, Instant now) {
        var failures = new HashMap<UUID, TopicRunDAO.FirstBuildFailures>();
        if (!found.isEmpty()) {
            template.inTransaction(READ_ONLY, handle -> {
                var runDAO = handle.attach(TopicRunDAO.class);
                found.stream().collect(Collectors.groupingBy(Candidate::workspaceId,
                        Collectors.mapping(Candidate::projectId, Collectors.toSet())))
                        .forEach((workspaceId, ids) -> runDAO.findFirstBuildFailures(workspaceId, ids)
                                .forEach(failure -> failures.put(failure.projectId(), failure)));
                return null;
            });
        }
        var due = new ArrayList<Candidate>();
        var waiting = new TreeMap<String, Long>();
        workspaceIds.forEach(workspaceId -> waiting.put(workspaceId, 0L));
        for (var candidate : found) {
            var failure = failures.get(candidate.projectId());
            if (failure == null) {
                due.add(candidate);
                continue;
            }
            if (!failure.firstStartedAt().isAfter(now.minus(WAITING_AFTER))) {
                waiting.merge(candidate.workspaceId(), 1L, Long::sum);
            }
            if (!failure.lastFinishedAt().plus(retryDelay(failure.failures())).isAfter(now)) {
                due.add(candidate);
            }
        }
        if (due.size() < found.size()) {
            log.info("Topics first builds left out '{}' of '{}' projects, they wait to retry a failed build",
                    found.size() - due.size(), found.size());
        }
        var waits = new TreeMap<String, Duration>();
        workspaceIds.forEach(workspaceId -> waits.put(workspaceId,
                longestWaits.getOrDefault(workspaceId, Duration.ZERO)));
        // Kept for the longest a run can last plus one interval: no other pod publishes while this run goes on.
        metrics.firstBuildState(waits, waiting, lease().plus(config.getFirstBuild().getInterval().toJavaDuration()));
        return due;
    }

    /**
     * Starts the project's first build now and returns its run id. The run goes on in the background with the same
     * timeout, and its outcome is its {@code topic_runs} row. The window ends at {@code night}'s start (UTC). The
     * workspace must be opted in to topics, the window must hold {@code minVectors} traces with a vector, and only one
     * run by hand goes at a time across the cluster. The project's vectors backfill must have drained, unless
     * {@code ignoreBackfill}: the operator's way to build a project whose backfill cannot drain. A failed first build's
     * wait does not apply.
     *
     * @throws ConflictException           when the workspace is not opted in, the window is too small, a run of the
     *                                     project or another run by hand is in progress, the project already has a
     *                                     list, or its vectors backfill has not drained and {@code ignoreBackfill} is
     *                                     false
     * @throws ServiceUnavailableException when Redis fails or does not answer while the run takes its locks, with a
     *                                     {@code Retry-After}
     */
    public UUID runNow(@NonNull String workspaceId, @NonNull UUID projectId, @NonNull LocalDate night,
            boolean ignoreBackfill) {
        if (!workspaceService.isEligible(workspaceId)) {
            throw new ConflictException(("Workspace '%s' is not opted in to topics: it needs to be in "
                    + "topics.enabledWorkspaces and enabled in ai_label_workspaces").formatted(workspaceId));
        }
        long vectors = windowVectors(workspaceId, projectId, night);
        if (vectors < config.getBuild().getMinVectors()) {
            throw new ConflictException(
                    "Project '%s' has '%d' vectors in the window until '%s', the minimum is '%d'".formatted(projectId,
                            vectors, night, config.getBuild().getMinVectors()));
        }
        if (!runner.lockByHand(BY_HAND_LOCK)) {
            throw new ConflictException("Another topics run started by hand is in progress");
        }
        boolean projectLocked;
        try {
            projectLocked = runner.lockByHand(TopicsProjectRunner.projectLock(projectId));
        } catch (RuntimeException exception) {
            runner.unlock(BY_HAND_LOCK);
            throw exception;
        }
        if (!projectLocked) {
            runner.unlock(BY_HAND_LOCK);
            throw new ConflictException("A topics run of project '%s' is in progress".formatted(projectId));
        }
        try {
            if (runner.hasList(workspaceId, projectId)) {
                throw new ConflictException("Project '%s' already has a topic list".formatted(projectId));
            }
            if (!ignoreBackfill) {
                var state = backfillGate.read(List.of(workspaceId), Instant.now()).state(workspaceId, projectId);
                if (!state.isOpen()) {
                    throw new ConflictException(("Project '%s' waits for its vectors backfill to drain, gate '%s'; "
                            + "ignore_backfill=true builds it anyway").formatted(projectId, state.getValue()));
                }
            }
            UUID runId;
            try {
                runId = rebuildService.startRun(workspaceId, projectId, TopicRun.Mode.FIRST_BUILD);
            } catch (RuntimeException exception) {
                metrics.run(workspaceId, TopicRun.Status.FAILED, TopicsMetrics.ERROR_START_FAILED);
                throw exception;
            }
            log.info("Topics run '{}' started by hand, workspace '{}', project '{}', window until '{}'", runId,
                    workspaceId, projectId, night);
            Thread.ofPlatform().name("topics-by-hand-" + projectId).daemon(true).start(() -> {
                var executor = Executors.newThreadPerTaskExecutor(
                        Thread.ofPlatform().name("topics-by-hand-run-", 0).daemon(true).factory());
                try {
                    runner.run(executor, runId, workspaceId, projectId, night);
                } finally {
                    executor.shutdownNow();
                    runner.unlockProject(projectId);
                    runner.unlock(BY_HAND_LOCK);
                }
            });
            return runId;
        } catch (RuntimeException exception) {
            runner.unlockProject(projectId);
            runner.unlock(BY_HAND_LOCK);
            throw exception;
        }
    }

    /**
     * The project's traces with a vector in the window that ends at {@code night}'s start, read within the workspace:
     * 0 for a project of another workspace.
     */
    private long windowVectors(String workspaceId, UUID projectId, LocalDate night) {
        var to = night.atStartOfDay(ZoneOffset.UTC).toInstant();
        var from = night.minusDays(config.getBuild().getWindowDays()).atStartOfDay(ZoneOffset.UTC).toInstant();
        return windowVectors(workspaceId, projectId, from, to);
    }

    private long windowVectors(String workspaceId, UUID projectId, Instant from, Instant to) {
        return windowDAO.countByProject(workspaceId, Set.of(projectId), from, to, TopicsRebuildService.COUNT_SECONDS)
                .collectList().block()
                .stream().filter(count -> count.projectId().equals(projectId))
                .mapToLong(TopicWindowDAO.ProjectCount::traces).findFirst().orElse(0);
    }
}

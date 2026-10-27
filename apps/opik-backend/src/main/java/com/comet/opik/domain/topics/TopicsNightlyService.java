package com.comet.opik.domain.topics;

import com.comet.opik.api.error.ConflictException;
import com.comet.opik.domain.ailabels.AiLabelWorkspaceDAO;
import com.comet.opik.domain.topics.TopicsRunException.Code;
import com.comet.opik.infrastructure.TopicsConfig;
import com.comet.opik.infrastructure.lock.LockService;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.Builder;
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
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;
import static com.comet.opik.infrastructure.lock.LockService.Lock;

/**
 * One night of the topics job: picks the projects to build and runs them one at a time.
 *
 * <p>A project is built when its workspace has ai-labels enabled and its window (the {@code windowDays} whole UTC days
 * before the night) holds at least {@code minVectors} traces with a vector. Stage 2a builds only a project without a
 * list; one with a saved run is skipped until the nightly update exists. Projects run smallest first, so small ones
 * always finish; none starts after {@code budget}, and those left run the next night. Each run gets its own thread and
 * is stopped (interrupted) and failed with {@code timeout} after {@code projectTimeout}.
 */
@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class TopicsNightlyService {

    /** Covers the housekeeping and the count reads before the first project, and the save after the last. */
    private static final Duration LEASE_SLACK = Duration.ofMinutes(5);

    private final @NonNull @Config("topics") TopicsConfig config;
    private final @NonNull TransactionTemplate template;
    private final @NonNull TopicWindowDAO windowDAO;
    private final @NonNull TopicsRebuildService rebuildService;
    private final @NonNull TopicsMetrics metrics;
    private final @NonNull LockService lockService;

    /** What one night did, by project. */
    @Builder(toBuilder = true)
    public record NightResult(int saved, int failed, int skipped, int notReached) {
    }

    private record Candidate(@NonNull String workspaceId, @NonNull UUID projectId, long vectors) {
    }

    /**
     * How long the night's lock must last: a project may start just before the budget ends and run its whole timeout.
     */
    public Duration lease() {
        var nightly = config.getNightly();
        return nightly.getBudget().toJavaDuration().plus(nightly.getProjectTimeout().toJavaDuration())
                .plus(LEASE_SLACK);
    }

    /**
     * Runs the night {@code start} falls on (UTC). The budget runs from the call, not from {@code start}, so a night
     * run late, or by hand for an earlier date, still gets all of it.
     */
    public NightResult runNight(@NonNull Instant start) {
        var began = Instant.now();
        var nightly = config.getNightly();
        var night = LocalDate.ofInstant(start, ZoneOffset.UTC);
        housekeeping(began);
        var candidates = new ArrayList<Candidate>();
        int skipped = eligible(night, candidates);
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
                    continue;
                }
                if (!lockProject(candidate.projectId())) {
                    log.info("Topics night skipped project '{}', a run started by hand holds it",
                            candidate.projectId());
                    skipped++;
                    metrics.project(TopicsMetrics.DECISION_SKIP, 1);
                    continue;
                }
                metrics.project(TopicsMetrics.DECISION_FIRST_BUILD, 1);
                try {
                    if (runOne(executor, candidate, night)) {
                        saved++;
                    } else {
                        failed++;
                    }
                } finally {
                    unlockProject(candidate.projectId());
                }
            }
        } finally {
            // Not close(): it waits for every task, and a run stopped by its timeout may ignore the interrupt.
            executor.shutdownNow();
        }
        metrics.project(TopicsMetrics.DECISION_NOT_REACHED, notReached);
        var used = Duration.between(began, Instant.now());
        metrics.budgetUsed(used);
        log.info("Topics night '{}' finished, saved '{}', failed '{}', skipped '{}', not reached '{}', seconds '{}'",
                night, saved, failed, skipped, notReached, used.toSeconds());
        return NightResult.builder().saved(saved).failed(failed).skipped(skipped).notReached(notReached).build();
    }

    /**
     * Starts a first build of the project now, outside the night, and returns its run id; the run goes on in the
     * background with the same timeout, and its outcome is its {@code topic_runs} row. The window is the one of
     * {@code night}: it ends at that day's start (UTC).
     *
     * @throws ConflictException when a run of the project is in progress, or it already has a topic list (stage 2a
     *                           builds a project once)
     */
    public UUID rebuildNow(@NonNull String workspaceId, @NonNull UUID projectId, @NonNull LocalDate night) {
        if (!lockProject(projectId)) {
            throw new ConflictException("A topics run of project '%s' is in progress".formatted(projectId));
        }
        try {
            var built = template.inTransaction(READ_ONLY,
                    handle -> handle.attach(TopicDAO.class).findStates(Set.of(projectId)));
            if (!built.isEmpty()) {
                throw new ConflictException(
                        "Project '%s' already has a topic list; the nightly update is not built yet"
                                .formatted(projectId));
            }
            var runId = rebuildService.startRun(workspaceId, projectId, TopicRun.Mode.FIRST_BUILD);
            log.info("Topics run '{}' started by hand, workspace '{}', project '{}'", runId, workspaceId, projectId);
            var candidate = new Candidate(workspaceId, projectId, 0);
            Thread.ofPlatform().name("topics-rebuild-" + projectId).daemon(true).start(() -> {
                var executor = Executors.newThreadPerTaskExecutor(
                        Thread.ofPlatform().name("topics-rebuild-run-", 0).daemon(true).factory());
                try {
                    run(executor, runId, candidate, night);
                } finally {
                    executor.shutdownNow();
                    unlockProject(projectId);
                }
            });
            return runId;
        } catch (RuntimeException exception) {
            unlockProject(projectId);
            throw exception;
        }
    }

    /** True when the run was saved. */
    private boolean runOne(ExecutorService executor, Candidate candidate, LocalDate night) {
        UUID runId;
        try {
            runId = rebuildService.startRun(candidate.workspaceId(), candidate.projectId(),
                    TopicRun.Mode.FIRST_BUILD);
        } catch (RuntimeException exception) {
            log.error("Topics run could not start, project '{}'", candidate.projectId(), exception);
            return false;
        }
        log.info("Topics run '{}' started, workspace '{}', project '{}', vectors '{}'", runId,
                candidate.workspaceId(), candidate.projectId(), candidate.vectors());
        return run(executor, runId, candidate, night);
    }

    /** Runs the build on its own thread, stopped after the project timeout. True when it was saved. */
    private boolean run(ExecutorService executor, UUID runId, Candidate candidate, LocalDate night) {
        var future = executor.submit(() -> rebuildService.firstBuild(runId, candidate.workspaceId(),
                candidate.projectId(), night));
        var timeout = config.getNightly().getProjectTimeout().toJavaDuration();
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS).status() == TopicRun.Status.SAVED;
        } catch (TimeoutException exception) {
            stop(runId, future, Code.TIMEOUT, "the run outlasted topics.nightly.projectTimeout '%s'"
                    .formatted(timeout));
            return false;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            stop(runId, future, Code.TIMEOUT, "the nightly job was interrupted");
            return false;
        } catch (ExecutionException exception) {
            // firstBuild records its own failures; this is a bug in it.
            log.error("Topics run '{}' threw, project '{}'", runId, candidate.projectId(), exception.getCause());
            stop(runId, future, Code.INTERNAL, String.valueOf(exception.getCause()));
            return false;
        }
    }

    /** Fails the run first, so a save racing the interrupt finds it ended and rolls back. */
    private void stop(UUID runId, Future<?> future, Code code, String message) {
        try {
            if (rebuildService.fail(runId, code, message, null)) {
                log.warn("Topics run '{}' failed, code '{}': {}", runId, code.getValue(), message);
            }
        } catch (RuntimeException exception) {
            log.error("Topics run '{}' could not be marked failed", runId, exception);
        }
        future.cancel(true);
    }

    /**
     * The project's lock, held for one run, so a run started by hand and the night never build it at once. Redis down:
     * not taken.
     */
    private boolean lockProject(UUID projectId) {
        try {
            return Boolean.TRUE.equals(lockService.lockUsingToken(projectLock(projectId), projectLease()).block());
        } catch (RuntimeException exception) {
            log.warn("Topics project lock unavailable, project '{}'", projectId, exception);
            return false;
        }
    }

    private void unlockProject(UUID projectId) {
        try {
            lockService.unlockUsingToken(projectLock(projectId)).block();
        } catch (RuntimeException exception) {
            log.warn("Topics project lock release failed, project '{}'; it expires with its lease", projectId,
                    exception);
        }
    }

    private Duration projectLease() {
        return config.getNightly().getProjectTimeout().toJavaDuration().plus(LEASE_SLACK);
    }

    static Lock projectLock(UUID projectId) {
        return new Lock("topics:project:" + projectId);
    }

    /**
     * Fills {@code candidates} with the projects to build, smallest first, and returns how many were skipped. A
     * workspace whose count read fails is left for the next night.
     */
    private int eligible(LocalDate night, List<Candidate> candidates) {
        var nightly = config.getNightly();
        var to = night.atStartOfDay(ZoneOffset.UTC).toInstant();
        var from = night.minusDays(nightly.getWindowDays()).atStartOfDay(ZoneOffset.UTC).toInstant();
        var workspaceIds = template.inTransaction(READ_ONLY,
                handle -> handle.attach(AiLabelWorkspaceDAO.class).enabledWorkspaceIds());
        int small = 0;
        var found = new ArrayList<Candidate>();
        for (var workspaceId : workspaceIds) {
            try {
                var counts = windowDAO.countByProject(workspaceId, from, to,
                        Math.max(1, nightly.getProjectTimeout().toSeconds())).collectList().block();
                for (var count : counts) {
                    if (count.traces() >= nightly.getMinVectors()) {
                        found.add(new Candidate(workspaceId, count.projectId(), count.traces()));
                    } else {
                        small++;
                    }
                }
            } catch (RuntimeException exception) {
                log.warn("Topics night count read failed, workspace '{}'; it runs the next night", workspaceId,
                        exception);
            }
        }
        var projectIds = found.stream().map(Candidate::projectId).collect(Collectors.toSet());
        Set<UUID> built = projectIds.isEmpty()
                ? Set.of()
                : template.inTransaction(READ_ONLY, handle -> handle.attach(TopicDAO.class).findStates(projectIds))
                        .stream().map(TopicProjectState::projectId).collect(Collectors.toSet());
        found.stream()
                .filter(candidate -> !built.contains(candidate.projectId()))
                .sorted(Comparator.comparingLong(Candidate::vectors).thenComparing(Candidate::projectId))
                .forEach(candidates::add);
        int skipped = small + (found.size() - candidates.size());
        metrics.project(TopicsMetrics.DECISION_SKIP, skipped);
        return skipped;
    }

    /**
     * Fails runs left {@code running} by a night that died (this night holds the lock, so none is live), and deletes
     * runs past {@code runsRetention}.
     */
    private void housekeeping(Instant start) {
        try {
            var retention = config.getNightly().getRunsRetention().toJavaDuration();
            template.inTransaction(WRITE, handle -> {
                var runs = handle.attach(TopicRunDAO.class);
                int abandoned = runs.failAbandoned(start, Instant.now());
                int deleted = runs.deleteStartedBefore(start.minus(retention));
                if (abandoned > 0 || deleted > 0) {
                    log.info("Topics night housekeeping, abandoned runs failed '{}', old runs deleted '{}'",
                            abandoned, deleted);
                }
                return null;
            });
        } catch (RuntimeException exception) {
            log.warn("Topics night housekeeping failed", exception);
        }
    }
}

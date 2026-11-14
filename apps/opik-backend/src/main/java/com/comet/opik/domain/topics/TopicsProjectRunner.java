package com.comet.opik.domain.topics;

import com.comet.opik.domain.topics.TopicsRunException.Code;
import com.comet.opik.infrastructure.TopicsConfig;
import com.comet.opik.infrastructure.lock.LockService;
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
import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;
import static com.comet.opik.infrastructure.lock.LockService.Lock;

/**
 * How one project's build runs, whoever starts it: under the project's lock, on its own thread, stopped and failed with
 * {@code timeout} after {@code topics.build.projectTimeout}. One run at a time per pod ({@link #acquireRun}). Also the
 * runs' housekeeping.
 */
@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class TopicsProjectRunner {

    /** Covers the reads before a run and the save after it in a lock's lease. */
    static final Duration LEASE_SLACK = Duration.ofMinutes(5);
    /** How long a run started by hand waits for Redis while it takes a lock. */
    private static final Duration LOCK_WAIT = Duration.ofSeconds(10);
    /** The {@code Retry-After} of a 503 when Redis fails or hangs while an endpoint takes a lock. */
    static final long LOCK_RETRY_AFTER_SECONDS = 30;

    private final @NonNull @Config("topics") TopicsConfig config;
    private final @NonNull TransactionTemplate template;
    private final @NonNull TopicsRebuildService rebuildService;
    private final @NonNull TopicsMetrics metrics;
    private final @NonNull LockService lockService;
    /**
     * The pod's one run slot: a first build or a nightly update holds its window in memory, so the first-build job, the
     * night and a run by hand on one pod take turns.
     */
    private final Semaphore runSlot = new Semaphore(1, true);

    /**
     * The longest one run holds its locks: its timeout, then the trace_topics insert of a run that saved just before
     * it ({@link #stop} waits for that insert).
     */
    public Duration runLimit() {
        return config.getBuild().getProjectTimeout().toJavaDuration().plus(TopicsRebuildService.INSERT_BOUND);
    }

    /** The lease of a project's lock, and of the lock of a run started by hand. */
    public Duration projectLease() {
        return runLimit().plus(LEASE_SLACK);
    }

    static Lock projectLock(@NonNull UUID projectId) {
        return new Lock("topics:project:" + projectId);
    }

    /**
     * Takes the pod's run slot, waiting up to one {@link #runLimit} (the longest a run on this pod holds it), and never
     * past {@code budgetEnd}: a project still starts within its job's budget, so the job's lock lease covers its run.
     * False when it stays taken; the caller then skips the project as locked. Taken before the project's lock and
     * before the run starts, so a skipped project holds no lock and leaves no run row. The caller gives it back with
     * {@link #releaseRun}.
     */
    public boolean acquireRun(@NonNull Instant budgetEnd) {
        var untilBudgetEnd = Duration.between(Instant.now(), budgetEnd);
        var limit = runLimit();
        return acquireRun(untilBudgetEnd.compareTo(limit) < 0 ? untilBudgetEnd : limit);
    }

    /** As {@link #acquireRun(Instant)}, waiting up to {@code wait}, none when it is not positive. */
    public boolean acquireRun(@NonNull Duration wait) {
        try {
            return runSlot.tryAcquire(wait.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public void releaseRun() {
        runSlot.release();
    }

    /** Takes the project's lock, held for one run. False when another run holds it, or Redis fails. */
    public boolean lockProject(@NonNull UUID projectId) {
        var lock = projectLock(projectId);
        try {
            return takeLock(lock);
        } catch (RuntimeException exception) {
            log.warn("Topics lock unavailable, lock '{}'", lock.key(), exception);
            return false;
        }
    }

    /**
     * Takes a lock for a run started by hand. False only when another holder has it: Redis failing or not answering
     * within {@link #LOCK_WAIT} throws a 503 with a {@code Retry-After}, not a 409.
     */
    public boolean lockByHand(@NonNull Lock lock) {
        try {
            return takeLock(lock);
        } catch (RuntimeException exception) {
            log.warn("Topics lock unavailable, lock '{}'", lock.key(), exception);
            throw new ServiceUnavailableException(
                    "The topics lock '%s' is unavailable, retry later".formatted(lock.key()), LOCK_RETRY_AFTER_SECONDS);
        }
    }

    public void unlockProject(@NonNull UUID projectId) {
        unlock(projectLock(projectId));
    }

    public void unlock(@NonNull Lock lock) {
        try {
            lockService.unlockUsingToken(lock).block();
        } catch (RuntimeException exception) {
            log.warn("Topics lock release failed, lock '{}'; it expires with its lease", lock.key(), exception);
        }
    }

    /** Throws when Redis fails, or does not answer within {@link #LOCK_WAIT}. */
    private boolean takeLock(Lock lock) {
        return Boolean.TRUE.equals(lockService.lockUsingToken(lock, projectLease()).block(LOCK_WAIT));
    }

    /** Whether the project has a saved list. Read under the project's lock, so a list built meanwhile is seen. */
    public boolean hasList(@NonNull String workspaceId, @NonNull UUID projectId) {
        return !template.inTransaction(READ_ONLY,
                handle -> handle.attach(TopicDAO.class).findStates(workspaceId, Set.of(projectId))).isEmpty();
    }

    /**
     * Runs the project's first build or nightly update ({@code mode}) for {@code night} on its own thread of
     * {@code executor}, and stops it after the project timeout. The caller holds the project's lock. True when the run
     * was saved.
     */
    public boolean run(@NonNull ExecutorService executor, @NonNull UUID runId, @NonNull String workspaceId,
            @NonNull UUID projectId, @NonNull LocalDate night, @NonNull TopicRun.Mode mode) {
        var future = executor.submit(() -> mode == TopicRun.Mode.NIGHTLY_UPDATE
                ? rebuildService.nightlyUpdate(runId, workspaceId, projectId, night)
                : rebuildService.firstBuild(runId, workspaceId, projectId, night));
        var timeout = config.getBuild().getProjectTimeout().toJavaDuration();
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS).status() == TopicRun.Status.SAVED;
        } catch (TimeoutException exception) {
            return stop(runId, workspaceId, projectId, future, Code.TIMEOUT,
                    "the run outlasted topics.build.projectTimeout '%s'".formatted(timeout));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return stop(runId, workspaceId, projectId, future, Code.TIMEOUT, "the job was interrupted");
        } catch (ExecutionException exception) {
            // firstBuild and nightlyUpdate record their own failures; this is a bug in them.
            log.error("Topics run '{}' threw, project '{}'", runId, projectId, exception.getCause());
            return stop(runId, workspaceId, projectId, future, Code.INTERNAL, String.valueOf(exception.getCause()));
        }
    }

    /**
     * Fails the run first, so a save racing the interrupt finds it ended and rolls back. True when the run had saved
     * already: the stop came after its MySQL commit, while its trace_topics insert went on. Such a run counts as saved,
     * and its insert is waited for (it ends within {@link TopicsRebuildService#INSERT_BOUND}), so the project's locks
     * are released only after it.
     */
    private boolean stop(UUID runId, String workspaceId, UUID projectId, Future<?> future, Code code,
            String message) {
        boolean saved = false;
        try {
            if (rebuildService.fail(runId, workspaceId, code, message, null)) {
                log.warn("Topics run '{}' failed, code '{}': {}", runId, code.getValue(), message);
            } else if (savedRun(runId, workspaceId)) {
                saved = true;
                log.warn("Topics run '{}' saved, project '{}', but its trace_topics insert outlasted the stop: {}",
                        runId, projectId, message);
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
     * Fails runs left {@code running} by a pod that died, and deletes runs past {@code topics.build.runsRetention}. A
     * run counts as left only once it is older than a project's lease: a younger one may be live, guarded only by its
     * project's lock. Each run failed so is counted, as {@code abandoned}, once the transaction commits. A failure is
     * logged, and the caller goes on.
     */
    public void housekeeping(@NonNull Instant now) {
        try {
            var abandoned = template.inTransaction(WRITE, handle -> {
                var runs = handle.attach(TopicRunDAO.class);
                var startedBefore = now.minus(projectLease());
                var workspaceIds = runs.findAbandonedWorkspaceIds(startedBefore);
                int failed = runs.failAbandoned(startedBefore, Instant.now());
                int deleted = runs.deleteStartedBefore(
                        now.minus(config.getBuild().getRunsRetention().toJavaDuration()));
                if (failed > 0 || deleted > 0) {
                    log.info("Topics runs housekeeping, abandoned runs failed '{}', old runs deleted '{}'", failed,
                            deleted);
                }
                return workspaceIds;
            });
            abandoned.forEach(workspaceId -> metrics.run(workspaceId, TopicRun.Status.FAILED,
                    TopicsMetrics.ERROR_ABANDONED));
        } catch (RuntimeException exception) {
            log.warn("Topics runs housekeeping failed", exception);
        }
    }

    /**
     * Deletes the topics, prototypes, states and runs of the workspaces' projects that no longer exist: what a failed
     * delete on {@code ProjectsDeleted} left behind, or a save that committed after its project was deleted. A
     * failure is logged, and the caller goes on.
     */
    public void deleteOfMissingProjects(@NonNull Collection<String> workspaceIds) {
        if (workspaceIds.isEmpty()) {
            return;
        }
        try {
            template.inTransaction(WRITE, handle -> {
                var topicDAO = handle.attach(TopicDAO.class);
                int topics = topicDAO.deleteTopicsOfMissingProjects(workspaceIds);
                int prototypes = topicDAO.deletePrototypesOfMissingProjects(workspaceIds);
                int states = topicDAO.deleteStatesOfMissingProjects(workspaceIds);
                int runs = handle.attach(TopicRunDAO.class).deleteOfMissingProjects(workspaceIds);
                if (topics + prototypes + states + runs > 0) {
                    log.info("Deleted the topic data of deleted projects, topics '{}', prototypes '{}', states '{}', "
                            + "runs '{}'", topics, prototypes, states, runs);
                }
                return null;
            });
        } catch (RuntimeException exception) {
            log.warn("Topics delete of deleted projects' data failed", exception);
        }
    }
}

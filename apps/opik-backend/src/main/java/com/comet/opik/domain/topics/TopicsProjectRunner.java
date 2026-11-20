package com.comet.opik.domain.topics;

import com.comet.opik.domain.topics.TopicProject.RunError;
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
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;
import static com.comet.opik.infrastructure.lock.LockService.Lock;

/**
 * How one project's build runs, whoever starts it: under the project's lock, on its own thread, stopped and failed with
 * {@code timeout} after {@code topics.build.projectTimeout}. Also the delete of deleted projects' topics.
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
    private final @NonNull LockService lockService;

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
        return project(workspaceId, projectId).map(TopicProject::listRunId).isPresent();
    }

    /**
     * Starts a first build of the project and returns its run id, recording first an attempt its pod left running past
     * a lock's lease. The caller holds the project's lock.
     */
    public UUID startFirstBuild(@NonNull String workspaceId, @NonNull UUID projectId) {
        return rebuildService.startRun(workspaceId, projectId, TopicProject.RunMode.FIRST_BUILD,
                Instant.now().minus(projectLease()));
    }

    /**
     * Runs the project's first build for {@code night} on its own thread of {@code executor}, and stops it after the
     * project timeout. The caller holds the project's lock. True when the run was saved.
     */
    public boolean run(@NonNull ExecutorService executor, @NonNull UUID runId, @NonNull String workspaceId,
            @NonNull UUID projectId, @NonNull LocalDate night) {
        var future = executor.submit(() -> rebuildService.firstBuild(runId, workspaceId, projectId, night));
        var timeout = config.getBuild().getProjectTimeout().toJavaDuration();
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS).status() == TopicProject.RunStatus.SAVED;
        } catch (TimeoutException exception) {
            return stop(runId, workspaceId, projectId, future, RunError.TIMEOUT,
                    "the run outlasted topics.build.projectTimeout '%s'".formatted(timeout));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return stop(runId, workspaceId, projectId, future, RunError.TIMEOUT, "the job was interrupted");
        } catch (ExecutionException exception) {
            // firstBuild records its own failures; this is a bug in it.
            log.error("Topics run '{}' threw, project '{}'", runId, projectId, exception.getCause());
            return stop(runId, workspaceId, projectId, future, RunError.INTERNAL, String.valueOf(exception.getCause()));
        }
    }

    /**
     * Fails the run first, so a save racing the interrupt finds it ended and rolls back. True when the run had saved
     * already: the stop came after its MySQL commit, while its trace_topics insert went on. Such a run counts as saved,
     * and its insert is waited for (it ends within {@link TopicsRebuildService#INSERT_BOUND}), so the project's locks
     * are released only after it.
     */
    private boolean stop(UUID runId, String workspaceId, UUID projectId, Future<?> future, RunError code,
            String message) {
        boolean saved = false;
        try {
            if (rebuildService.fail(runId, workspaceId, projectId, code)) {
                log.warn("Topics run '{}' failed, project '{}', code '{}': {}", runId, projectId, code.getValue(),
                        message);
            } else if (savedRun(runId, workspaceId, projectId)) {
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

    /** Whether the project's attempt is the run, saved. A run that failed on its own logged its failure already. */
    private boolean savedRun(UUID runId, String workspaceId, UUID projectId) {
        return project(workspaceId, projectId)
                .filter(project -> runId.equals(project.runId())
                        && project.runStatus() == TopicProject.RunStatus.SAVED)
                .isPresent();
    }

    private Optional<TopicProject> project(String workspaceId, UUID projectId) {
        return template.inTransaction(READ_ONLY,
                handle -> handle.attach(TopicDAO.class).findProjects(workspaceId, Set.of(projectId))).stream()
                .findFirst();
    }

    /**
     * Deletes the topics of the workspaces' projects that no longer exist: what a failed delete on
     * {@code ProjectsDeleted} left behind, or a save that committed after its project was deleted. Their
     * {@code topic_projects} rows go with the vectors tick's delete. A failure is logged, and the caller goes on.
     */
    public void deleteOfMissingProjects(@NonNull Collection<String> workspaceIds) {
        if (workspaceIds.isEmpty()) {
            return;
        }
        try {
            int topics = template.inTransaction(WRITE,
                    handle -> handle.attach(TopicDAO.class).deleteTopicsOfMissingProjects(workspaceIds));
            if (topics > 0) {
                log.info("Deleted the topics of deleted projects, topics '{}'", topics);
            }
        } catch (RuntimeException exception) {
            log.warn("Topics delete of deleted projects' data failed", exception);
        }
    }
}

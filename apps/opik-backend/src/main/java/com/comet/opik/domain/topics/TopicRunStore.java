package com.comet.opik.domain.topics;

import com.comet.opik.domain.IdGenerator;
import com.comet.opik.domain.ProjectService;
import com.comet.opik.domain.WorkspaceNameService;
import com.comet.opik.domain.topics.TopicProject.RunError;
import com.comet.opik.infrastructure.TopicsConfig;
import com.comet.opik.utils.RetryUtils;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.function.ToIntFunction;

import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;

/**
 * A run's writes: its attempt on the project's {@code topic_projects} row (queue, claim, heartbeat, requeue, cancel,
 * fail; each a compare-and-set, see {@link TopicProjectDAO}), the save transaction and the {@code trace_topics} insert
 * after it. MySQL calls are blocking JDBI, each wrapped once on a bounded-elastic thread.
 */
@Slf4j
@Singleton
class TopicRunStore {

    private static final Duration INSERT_BOUND_SLACK = Duration.ofSeconds(30);

    private final TopicsConfig.BuildConfig build;
    private final TransactionTemplate template;
    private final TraceTopicsDAO traceTopicsDAO;
    private final ProjectService projectService;
    private final IdGenerator idGenerator;
    private final TopicsMetrics metrics;
    private final WorkspaceNameService workspaceNames;
    private final Scheduler jdbi = Schedulers.boundedElastic();

    @Inject
    TopicRunStore(@NonNull @Config("topics") TopicsConfig config, @NonNull TransactionTemplate template,
            @NonNull TraceTopicsDAO traceTopicsDAO, @NonNull ProjectService projectService,
            @NonNull IdGenerator idGenerator, @NonNull TopicsMetrics metrics,
            @NonNull WorkspaceNameService workspaceNames) {
        this.build = config.getBuild();
        this.template = template;
        this.traceTopicsDAO = traceTopicsDAO;
        this.projectService = projectService;
        this.idGenerator = idGenerator;
        this.metrics = metrics;
        this.workspaceNames = workspaceNames;
    }

    /**
     * Queues a run of the project, creating its row when it has none. Empty when the project has a run in flight: the
     * row is the only lock, so of two callers one queues and the other gets empty.
     */
    Mono<Optional<UUID>> queue(@NonNull String workspaceId, @NonNull UUID projectId,
            @NonNull TopicProject.RunMode mode) {
        return jdbi(() -> {
            var runId = idGenerator.generateId();
            int queued = template.inTransaction(WRITE, handle -> {
                var projectDAO = handle.attach(TopicProjectDAO.class);
                projectDAO.ensureRow(workspaceId, projectId);
                return projectDAO.queue(workspaceId, projectId, runId, mode, Instant.now());
            });
            return queued == 1 ? Optional.of(runId) : Optional.empty();
        });
    }

    /** {@code queued -> running} until {@code deadline}. False when another pod claimed it, or it was cancelled. */
    Mono<Boolean> claim(@NonNull TopicsRunMessage message, @NonNull Instant deadline) {
        return write(dao -> dao.claim(message.workspaceId(), message.projectId(), message.runId(), Instant.now(),
                deadline));
    }

    /**
     * True while the run should go on: its row is still running, or the run saved it itself. A saved run still has
     * its {@code trace_topics} insert and its labels run to queue, so a heartbeat between the save and the end of the
     * run must not stop it. False once the row was cancelled, abandoned or requeued.
     */
    Mono<Boolean> heartbeat(@NonNull TopicsRunMessage message) {
        return jdbi(() -> template.inTransaction(WRITE, handle -> {
            var dao = handle.attach(TopicProjectDAO.class);
            return dao.heartbeat(message.workspaceId(), message.projectId(), message.runId(), Instant.now()) == 1
                    || dao.findRunStatus(message.workspaceId(), message.projectId(), message.runId())
                            .filter(status -> status == TopicProject.RunStatus.SAVED)
                            .isPresent();
        }));
    }

    /** {@code running -> queued}, for a pod that stops: another pod runs it again, and it is not a failure. */
    Mono<Boolean> requeue(@NonNull TopicsRunMessage message) {
        return write(dao -> dao.requeue(message.workspaceId(), message.projectId(), message.runId(), Instant.now()));
    }

    /**
     * {@code cancelling -> failed}, once the run has stopped, and counts it in {@code topics.runs}. Empty when it was
     * not cancelling: it ended, or was requeued.
     */
    Mono<Optional<RunError>> endCancelled(@NonNull String workspaceId, @NonNull UUID projectId, @NonNull UUID runId) {
        return jdbi(() -> template.inTransaction(WRITE, handle -> {
            var dao = handle.attach(TopicProjectDAO.class);
            return dao.endCancelled(workspaceId, projectId, runId, Instant.now()) == 1
                    ? dao.findRunError(workspaceId, projectId, runId)
                    : Optional.<RunError>empty();
        })).doOnNext(code -> code.ifPresent(value -> countFailed(workspaceId, value)));
    }

    /** {@code queued -> failed} with {@code cancelled}: its project or workspace left. */
    Mono<Boolean> dropQueued(@NonNull String workspaceId, @NonNull UUID projectId, @NonNull UUID runId,
            @NonNull TopicProject.CancelReason reason) {
        return write(dao -> dao.dropQueued(workspaceId, projectId, runId, reason, Instant.now()))
                .doOnNext(dropped -> {
                    if (dropped) {
                        countFailed(workspaceId, RunError.CANCELLED);
                    }
                });
    }

    /**
     * {@code queued -> failed} with {@code cancelled}, for a run by hand that was not published: false when a pod
     * claimed it meanwhile, which stays true for the run even after a requeue ({@link #isClaimed}).
     */
    Mono<Boolean> dropUnclaimed(@NonNull String workspaceId, @NonNull UUID projectId, @NonNull UUID runId) {
        return write(dao -> dao.dropUnclaimed(workspaceId, projectId, runId, Instant.now()))
                .doOnNext(dropped -> {
                    if (dropped) {
                        countFailed(workspaceId, RunError.CANCELLED);
                    }
                });
    }

    /** {@code running | cancelling -> failed} with {@code abandoned}, when its heartbeat is older than {@code before}. */
    Mono<Boolean> abandon(@NonNull InFlightRun run, @NonNull Instant before) {
        return write(dao -> dao.abandon(run.workspaceId(), run.projectId(), run.runId(), before, Instant.now()))
                .doOnNext(abandoned -> {
                    if (abandoned) {
                        countFailed(run.workspaceId(), RunError.ABANDONED);
                    }
                });
    }

    /** Counts a failed run its pod did not end itself. Off the reactive threads: the name may be an HTTP call. */
    private void countFailed(String workspaceId, RunError code) {
        Mono.fromRunnable(() -> metrics.run(workspace(workspaceId), TopicProject.RunStatus.FAILED, code.getValue()))
                .subscribeOn(jdbi)
                .subscribe(null, error -> log.warn("Topics run metric not recorded, workspace '{}'", workspaceId,
                        error));
    }

    /** The metrics' workspace; its name falls back to the id. */
    private TopicsMetrics.Workspace workspace(String workspaceId) {
        try {
            return new TopicsMetrics.Workspace(workspaceId, workspaceNames.nameOrId(workspaceId));
        } catch (RuntimeException exception) {
            return new TopicsMetrics.Workspace(workspaceId, workspaceId);
        }
    }

    /** {@code running -> saved} for a labelling pass, which has no save transaction of its own. */
    Mono<Boolean> endLabels(@NonNull TopicsRunMessage message) {
        return write(dao -> dao.markSaved(message.workspaceId(), message.projectId(), message.runId(),
                Instant.now()));
    }

    /**
     * Cancels the projects' runs in flight, for a project delete or an opt-out: a queued run fails at once, a running
     * one stops at its next heartbeat. Returns how many it cancelled.
     */
    Mono<Integer> cancelProjects(@NonNull String workspaceId, @NonNull Collection<UUID> projectIds,
            @NonNull TopicProject.CancelReason reason) {
        if (projectIds.isEmpty()) {
            return Mono.just(0);
        }
        return jdbi(() -> template.inTransaction(WRITE, handle -> handle.attach(TopicProjectDAO.class)
                .cancelProjects(workspaceId, projectIds, reason, Instant.now())));
    }

    /** True when the project's attempt is still {@code runId} and a pod claimed it, even if it was requeued since. */
    Mono<Boolean> isClaimed(@NonNull String workspaceId, @NonNull UUID projectId, @NonNull UUID runId) {
        return jdbi(() -> template.inTransaction(READ_ONLY, handle -> handle.attach(TopicProjectDAO.class)
                .isClaimed(workspaceId, projectId, runId)));
    }

    /** Every run in flight, of any workspace. */
    Mono<List<InFlightRun>> findInFlight() {
        return jdbi(() -> template.inTransaction(READ_ONLY, handle -> handle.attach(TopicProjectDAO.class)
                .findInFlight()));
    }

    /** One compare-and-set on a run's row, in its own transaction: true when it moved the row. */
    Mono<Boolean> write(@NonNull ToIntFunction<TopicProjectDAO> statement) {
        return jdbi(() -> template.inTransaction(WRITE, handle -> statement.applyAsInt(
                handle.attach(TopicProjectDAO.class))) == 1);
    }

    /**
     * Fails the run unless it already ended. False when it had: the run was saved, or failed already. Counts the run
     * in {@code topics.runs} only when this mark ends its attempt. A mark that fails leaves the attempt {@code running}
     * and counts nothing: the project's next attempt records it, and counts it as {@code abandoned}, so every run is
     * counted once.
     */
    Mono<Boolean> fail(@NonNull UUID runId, @NonNull TopicsMetrics.Workspace workspace, @NonNull UUID projectId,
            @NonNull RunError code) {
        return jdbi(() -> {
            int updated = template.inTransaction(WRITE, handle -> handle.attach(TopicProjectDAO.class)
                    .markFailed(workspace.id(), projectId, runId, Instant.now(), code));
            if (updated == 1) {
                metrics.run(workspace, TopicProject.RunStatus.FAILED, code.getValue());
            }
            return updated == 1;
        });
    }

    /** Whether the project's attempt is the run, saved. */
    Mono<Boolean> isSaved(@NonNull TopicRun run) {
        return jdbi(() -> template.inTransaction(READ_ONLY, handle -> handle.attach(TopicProjectDAO.class)
                .findProjects(run.workspaceId(), Set.of(run.projectId()))).stream()
                .anyMatch(project -> run.runId().equals(project.runId())
                        && project.runStatus() == TopicProject.RunStatus.SAVED));
    }

    /**
     * Saves the list in one transaction: the list on the project's row, the new topics with their prototype vectors,
     * and the run as {@code saved}. The list is a compare-and-set on an empty {@code list_run_id}, as a first build
     * saves a project that has no list: if another run saved the project anyway, the save is a conflict. A run that is no
     * longer {@code running} (failed by its timeout, cancelled, abandoned or requeued) rolls the save back.
     *
     * <p>The project's row is not locked. After the commit the save reads it: when the project is gone, the run's topics
     * are deleted and the result is false, so the caller skips the {@code trace_topics} insert. A delete that commits
     * after that read leaves the topics to the daily sweep of deleted projects.
     */
    Mono<Boolean> save(@NonNull TopicRun run, @NonNull List<Topic> topics, PrototypeVectors otherVectors) {
        return jdbi(() -> {
            var now = Instant.now();
            template.inTransaction(WRITE, handle -> {
                var topicDAO = handle.attach(TopicDAO.class);
                var projectDAO = handle.attach(TopicProjectDAO.class);
                // First, so a run that saved this project meanwhile fails the save before anything else.
                if (projectDAO.saveList(run.workspaceId(), run.projectId(), null, run.runId(), now, run.to(),
                        otherVectors, TopicEmbedding.MODEL) != 1) {
                    throw new TopicsRunException(RunError.SAVE, "another run saved this project meanwhile");
                }
                if (!topics.isEmpty()) {
                    topicDAO.insertTopics(topics);
                }
                if (projectDAO.markSaved(run.workspaceId(), run.projectId(), run.runId(),
                        now) != 1) {
                    throw new TopicsRunException(RunError.TIMEOUT, "the run ended before its save committed");
                }
                return null;
            });
            metrics.run(run.workspace(), TopicProject.RunStatus.SAVED, null);
            metrics.saved(run.workspace(), "topics", topics.size());
            return true;
        })
                .onErrorMap(error -> !(error instanceof TopicsRunException),
                        error -> new TopicsRunException(RunError.SAVE, "the save failed", error))
                .flatMap(saved -> keepIfProjectExists(run));
    }

    /** Whether the project is still in {@code projects}. */
    Mono<Boolean> projectExists(@NonNull String workspaceId, @NonNull UUID projectId) {
        return jdbi(() -> exists(workspaceId, projectId));
    }

    private boolean exists(String workspaceId, UUID projectId) {
        return !projectService.findByIds(workspaceId, Set.of(projectId)).isEmpty();
    }

    /** False, after deleting the run's topics, when the project was deleted during the run. */
    private Mono<Boolean> keepIfProjectExists(TopicRun run) {
        return jdbi(() -> {
            boolean exists = exists(run.workspaceId(), run.projectId());
            if (!exists) {
                int deleted = template.inTransaction(WRITE, handle -> handle.attach(TopicDAO.class)
                        .deleteTopicsByProjects(run.workspaceId(), List.of(run.projectId())));
                log.warn("Topics run '{}' saved after its project was deleted, project '{}': its result is discarded, "
                        + "topics deleted '{}'", run.runId(), run.projectId(), deleted);
            }
            return exists;
        }).onErrorResume(error -> {
            // The run is saved: a failed check leaves a deleted project's topics to the sweep.
            log.warn("Topics run '{}' could not check its project after the save, project '{}'", run.runId(),
                    run.projectId(), error);
            return Mono.just(true);
        });
    }

    /** The longest the insert after the commit takes: every attempt to its stop, the backoffs, and some slack. */
    static Duration insertBound(@NonNull TopicsConfig.BuildConfig build) {
        int retries = build.getInsertRetries();
        var backoffs = build.getInsertBackoff().toJavaDuration().multipliedBy((1L << retries) - 1);
        return build.getReadTimeout().toJavaDuration().multipliedBy(retries + 1L).plus(backoffs)
                .plus(INSERT_BOUND_SLACK);
    }

    /**
     * Inserts the saved run's trace topics. A transient error is retried up to {@code topics.build.insertRetries}
     * times (a repeat writes the same rows, one per trace); any other fails at once. Counted by result. Each attempt stops after {@code topics.build.readTimeout}. False when it still failed:
     * the labelling pass gives the window's traces their topics.
     */
    Mono<Boolean> insertTraceTopics(@NonNull TopicRun run, @NonNull List<TraceTopic> traceTopics) {
        var backoff = build.getInsertBackoff().toJavaDuration();
        return traceTopicsDAO.insert(traceTopics)
                .timeout(build.getReadTimeout().toJavaDuration())
                .retryWhen(RetryUtils.handleClickHouseErrors(build.getInsertRetries(), backoff,
                        backoff.multipliedBy(1L << build.getInsertRetries()))
                        .doBeforeRetry(signal -> log.warn("Topics run '{}' retries its trace_topics insert, "
                                + "project '{}', attempt '{}'", run.runId(), run.projectId(),
                                signal.totalRetries() + 2, signal.failure())))
                .map(__ -> {
                    metrics.saved(run.workspace(), "trace_topics", traceTopics.size());
                    metrics.traceTopicsInsert(run.workspace(), true);
                    return true;
                })
                .onErrorResume(exception -> {
                    metrics.traceTopicsInsert(run.workspace(), false);
                    log.error("Topics run '{}' saved, but its trace_topics insert failed, project '{}'; the labelling "
                            + "pass gives the window's traces their topics", run.runId(), run.projectId(), exception);
                    return Mono.just(false);
                });
    }

    private <T> Mono<T> jdbi(Callable<T> call) {
        return Mono.fromCallable(call).subscribeOn(jdbi);
    }
}

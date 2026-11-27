package com.comet.opik.domain.topics;

import com.comet.opik.domain.IdGenerator;
import com.comet.opik.domain.ProjectService;
import com.comet.opik.domain.WorkspaceNameService;
import com.comet.opik.domain.topics.TopicProject.RunError;
import com.comet.opik.infrastructure.TopicsConfig;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.utils.RetryUtils;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.Builder;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.function.ToIntFunction;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;

/**
 * A run's MySQL state and its {@code trace_topics} writes: its attempt on the project's {@code topic_projects} row
 * (queue, claim, heartbeat, requeue, cancel, fail; each a compare-and-set, see {@link TopicProjectDAO}), the save
 * transaction, the saved list the labelling pass reads and marks complete, and the {@code trace_topics} insert both the
 * run and the pass use. MySQL calls are blocking JDBI, each wrapped once on a bounded-elastic thread.
 */
@Slf4j
@Singleton
class TopicRunStore {

    private static final List<TopicProject.RunStatus> ENDED = Stream.of(TopicProject.RunStatus.values())
            .filter(status -> !status.inFlight())
            .toList();

    private final TopicsConfig.RunConfig runConfig;
    private final TransactionTemplate template;
    private final TraceTopicsDAO traceTopicsDAO;
    private final ProjectService projectService;
    private final IdGenerator idGenerator;
    private final TopicsMetrics metrics;
    private final WorkspaceNameService workspaceNames;

    @Inject
    TopicRunStore(@NonNull @Config("topics") TopicsConfig config, @NonNull TransactionTemplate template,
            @NonNull TraceTopicsDAO traceTopicsDAO, @NonNull ProjectService projectService,
            @NonNull IdGenerator idGenerator, @NonNull TopicsMetrics metrics,
            @NonNull WorkspaceNameService workspaceNames) {
        this.runConfig = config.getRun();
        this.template = template;
        this.traceTopicsDAO = traceTopicsDAO;
        this.projectService = projectService;
        this.idGenerator = idGenerator;
        this.metrics = metrics;
        this.workspaceNames = workspaceNames;
    }

    /**
     * Queues a run of the project, creating its row when it has none, and returns its id. Empty when the project has a
     * run in flight, or its row no longer fits the mode ({@link TopicProjectDAO#queue}): the row is the only lock, so
     * of two callers one queues and the other gets empty.
     */
    Mono<UUID> queue(@NonNull String workspaceId, @NonNull UUID projectId, @NonNull TopicProject.RunMode mode) {
        var runId = idGenerator.generateId();
        return jdbi(() -> template.inTransaction(WRITE, handle -> {
            var projectDAO = handle.attach(TopicProjectDAO.class);
            projectDAO.ensureRow(workspaceId, projectId);
            return projectDAO.queue(workspaceId, projectId, runId, mode, Instant.now());
        }))
                .filter(queued -> queued == 1)
                .map(queued -> runId);
    }

    /** {@code queued -> running} until {@code deadline}. False when another pod claimed it, or it was cancelled. */
    Mono<Boolean> claim(@NonNull TopicsRunMessage message, @NonNull Instant deadline) {
        return write(dao -> dao.claim(message.workspaceId(), message.projectId(), message.runId(), Instant.now(),
                deadline));
    }

    /**
     * {@code queued -> running} for a nightly update, re-checking the night's skips on the row: a run that finds its
     * project without a list, or its list already covering the night, ends {@code failed} with {@code cancelled} (not
     * counted in {@code failed_runs}) and is counted as that skip in {@code topics.nightly.decisions}.
     */
    Mono<NightClaim> claimUpdate(@NonNull TopicsRunMessage message, @NonNull Instant deadline) {
        var windowTo = TopicsNight.windowTo(message.night());
        return jdbi(() -> template.inTransaction(WRITE, handle -> {
            var dao = handle.attach(TopicProjectDAO.class);
            var now = Instant.now();
            if (dao.claimUpdate(message.workspaceId(), message.projectId(), message.runId(), now, deadline,
                    windowTo) == 1) {
                return NightClaim.CLAIMED;
            }
            if (dao.skipUpdate(message.workspaceId(), message.projectId(), message.runId(), now, windowTo) != 1) {
                return NightClaim.LOST;
            }
            return dao.findProjects(message.workspaceId(), List.of(message.projectId())).stream()
                    .anyMatch(project -> project.listRunId() != null)
                            ? NightClaim.ALREADY_SAVED
                            : NightClaim.NO_LIST;
        }))
                .flatMap(claim -> {
                    var reason = switch (claim) {
                        case NO_LIST -> NightSkipReason.NO_LIST;
                        case ALREADY_SAVED -> NightSkipReason.ALREADY_SAVED;
                        case CLAIMED, LOST -> null;
                    };
                    if (reason == null) {
                        return Mono.just(claim);
                    }
                    log.info("Topics run '{}' skipped at its claim, project '{}', night '{}', reason '{}'",
                            message.runId(), message.projectId(), message.night(), reason.getValue());
                    return resolveWorkspace(message.workspaceId())
                            .doOnNext(workspace -> metrics.nightlyDecision(workspace, NightDecision.SKIP, reason, 1))
                            .thenReturn(claim);
                });
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
    Mono<RunError> endCancelled(@NonNull String workspaceId, @NonNull UUID projectId, @NonNull UUID runId) {
        return jdbi(() -> template.inTransaction(WRITE, handle -> {
            var dao = handle.attach(TopicProjectDAO.class);
            return dao.endCancelled(workspaceId, projectId, runId, Instant.now()) == 1
                    ? dao.findRunError(workspaceId, projectId, runId).orElse(null)
                    : null;
        })).flatMap(code -> countFailed(workspaceId, code).thenReturn(code));
    }

    /** {@code queued -> failed} with {@code cancelled}: its project or workspace left. */
    Mono<Boolean> dropQueued(@NonNull String workspaceId, @NonNull UUID projectId, @NonNull UUID runId,
            @NonNull TopicProject.CancelReason reason) {
        return write(dao -> dao.dropQueued(workspaceId, projectId, runId, reason, Instant.now()))
                .flatMap(dropped -> counted(dropped, workspaceId, RunError.CANCELLED));
    }

    /**
     * {@code queued -> failed} with {@code cancelled}, for a run by hand that was not published: false when a pod
     * claimed it meanwhile, which stays true for the run even after a requeue ({@link #isClaimed}).
     */
    Mono<Boolean> dropUnclaimed(@NonNull String workspaceId, @NonNull UUID projectId, @NonNull UUID runId) {
        return write(dao -> dao.dropUnclaimed(workspaceId, projectId, runId, Instant.now()))
                .flatMap(dropped -> counted(dropped, workspaceId, RunError.CANCELLED));
    }

    /** {@code running | cancelling -> failed} with {@code abandoned}, when its heartbeat is older than {@code before}. */
    Mono<Boolean> abandon(@NonNull InFlightRun run, @NonNull Instant before) {
        return write(dao -> dao.abandon(run.workspaceId(), run.projectId(), run.runId(), before, Instant.now()))
                .flatMap(abandoned -> counted(abandoned, run.workspaceId(), RunError.ABANDONED));
    }

    /** {@code moved}, after counting the failed run when the write moved its row. */
    private Mono<Boolean> counted(boolean moved, String workspaceId, RunError code) {
        return moved ? countFailed(workspaceId, code).thenReturn(true) : Mono.just(false);
    }

    /** Counts a failed run its pod did not end itself. On boundedElastic: the workspace name may be an HTTP call. */
    private Mono<Void> countFailed(String workspaceId, RunError code) {
        return Mono.fromRunnable(
                () -> metrics.run(workspace(workspaceId), TopicProject.RunStatus.FAILED, code.getValue()))
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }

    /** The workspace with its name, on boundedElastic: the name may be an HTTP call. */
    Mono<TopicsMetrics.Workspace> resolveWorkspace(@NonNull String workspaceId) {
        return jdbi(() -> workspace(workspaceId));
    }

    /** The metrics' workspace; its name falls back to the id. */
    private TopicsMetrics.Workspace workspace(String workspaceId) {
        try {
            return new TopicsMetrics.Workspace(workspaceId, workspaceNames.nameOrId(workspaceId));
        } catch (RuntimeException exception) {
            return new TopicsMetrics.Workspace(workspaceId, workspaceId);
        }
    }

    /**
     * {@code running -> saved} for a labelling pass, which has no save transaction of its own. The workspace comes from
     * the Reactor context.
     */
    Mono<Boolean> endLabels(@NonNull UUID projectId, @NonNull UUID runId) {
        return Mono.deferContextual(ctx -> write(dao -> dao.markSaved(ctx.get(RequestContext.WORKSPACE_ID),
                projectId, runId, Instant.now())));
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
     * and the run as {@code saved}. The list is a compare-and-set on an empty {@code list_run_id}, as a discovery run
     * saves a project that has no list: if another run saved the project anyway, the save is a conflict. A run that is no
     * longer {@code running} (failed by its timeout, cancelled, abandoned or requeued) rolls the save back.
     *
     * <p>The project's row is not locked. After the commit, one delete removes the run's topics if the project is gone
     * ({@link #keepIfProjectExists}). A project deleted after that delete keeps its topics until {@code TopicsSweepJob}
     * deletes them.
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
                .flatMap(saved -> keepIfProjectExists(run, !topics.isEmpty()));
    }

    /**
     * The saved list as the night reads it ({@link #readSaved}): the topics in list order (born, then id) with their
     * vectors, the known-Other vectors, and the run that saved it, which the save checks is still the saved one. Fails
     * with {@code internal} when the project has no saved list.
     */
    Mono<NightList> readList(@NonNull TopicRun run, @NonNull LocalDate night) {
        return readSaved(run.workspaceId(), run.projectId())
                .filter(saved -> !saved.topics().isEmpty())
                .switchIfEmpty(Mono.error(() -> new TopicsRunException(RunError.INTERNAL,
                        "the project has no saved topic list")))
                .map(saved -> {
                    var otherVectors = saved.project().otherVectors();
                    var state = NightState.builder()
                            .topics(saved.topics().stream().map(TopicRunStore::nightTopic).toList())
                            .otherPrototypes(otherVectors == null ? List.of() : otherVectors.vectors())
                            .rebuilds(saved.project().savedRuns())
                            .night(night)
                            .ids(idGenerator::generateId)
                            .build();
                    return NightList.builder()
                            .state(state)
                            .listRunId(saved.project().listRunId())
                            .topicIds(saved.topics().stream().map(Topic::id)
                                    .collect(Collectors.toUnmodifiableSet()))
                            .build();
                });
    }

    private static NightTopic nightTopic(Topic topic) {
        return NightTopic.builder()
                .id(topic.id())
                .name(topic.name())
                .definition(topic.definition())
                .born(topic.born())
                .prototypes(topic.vectors().vectors())
                .lowNights(topic.lowNights())
                .size(topic.size())
                .build();
    }

    /**
     * Saves a nightly update in one transaction: the list on the project's row (a compare-and-set on the
     * {@code list_run_id} the run read), the retired topics deleted, the kept ones updated (lifecycle, size, vectors),
     * tonight's new ones inserted, the run saved. When no topic stayed live it clears the list instead
     * ({@link TopicProjectDAO#clearList}). A run failed meanwhile, by its timeout, rolls the save back.
     *
     * <p>As {@link #save}, the project's row is not locked: after the commit the save reads it, and when the project is
     * gone the topics are deleted and the result is false. False too when the list was cleared: either way the caller
     * inserts no {@code trace_topics}.
     */
    Mono<Boolean> saveUpdate(@NonNull TopicRun run, @NonNull NightList saved, @NonNull NightSave save) {
        return jdbi(() -> {
            var now = Instant.now();
            template.inTransaction(WRITE, handle -> {
                var topicDAO = handle.attach(TopicDAO.class);
                var projectDAO = handle.attach(TopicProjectDAO.class);
                // First, so a run that saved this project meanwhile fails the save before anything else.
                int listed = save.keep()
                        ? projectDAO.saveList(run.workspaceId(), run.projectId(), saved.listRunId(), run.runId(), now,
                                run.to(), save.otherVectors(), TopicEmbedding.MODEL)
                        : projectDAO.clearList(run.workspaceId(), run.projectId(), saved.listRunId(), now);
                if (listed != 1) {
                    throw new TopicsRunException(RunError.SAVE, "another run saved this project meanwhile");
                }
                if (!save.retired().isEmpty()) {
                    topicDAO.deleteTopics(run.workspaceId(), run.projectId(), save.retired());
                }
                if (!save.updated().isEmpty()) {
                    topicDAO.updateTopics(save.updated());
                }
                if (!save.inserted().isEmpty()) {
                    topicDAO.insertTopics(save.inserted());
                }
                if (projectDAO.markSaved(run.workspaceId(), run.projectId(), run.runId(), now) != 1) {
                    throw new TopicsRunException(RunError.TIMEOUT, "the run ended before its save committed");
                }
                return null;
            });
            metrics.run(run.workspace(), TopicProject.RunStatus.SAVED, null);
            metrics.saved(run.workspace(), "topics",
                    save.inserted().size() + save.updated().size() + save.retired().size());
            return true;
        })
                .onErrorMap(error -> !(error instanceof TopicsRunException),
                        error -> new TopicsRunException(RunError.SAVE, "the save failed", error))
                .flatMap(__ -> keepIfProjectExists(run, save.keep()));
    }

    /** Whether the project is still in {@code projects}. The workspace comes from the Reactor context. */
    Mono<Boolean> projectExists(@NonNull UUID projectId) {
        return Mono.deferContextual(ctx -> jdbi(() -> !projectService
                .findByIds(ctx.<String>get(RequestContext.WORKSPACE_ID), Set.of(projectId)).isEmpty()));
    }

    /**
     * True when the project still exists, so the caller inserts the run's {@code trace_topics}. One delete decides it:
     * it removes the run's topics only when the project is gone, so a project deleted at any point before it loses its
     * topics. False, so the insert is skipped, when the project was deleted, when the check failed after its deadlock
     * retries, or when the run saved no topics (the delete cannot tell then). Skipping leaves the labels incomplete: the
     * labelling pass labels the window if the project is still there, and the planner cancels it if it is not.
     */
    private Mono<Boolean> keepIfProjectExists(TopicRun run, boolean savedTopics) {
        if (!savedTopics) {
            return Mono.just(false);
        }
        return jdbi(() -> template.inTransaction(WRITE, handle -> handle.attach(TopicDAO.class)
                .deleteTopicsOfMissingProject(run.workspaceId(), run.projectId())))
                .retryWhen(RetryUtils.handleOnDeadLocks())
                .map(deleted -> {
                    if (deleted > 0) {
                        log.warn("Topics run '{}' saved after its project was deleted, project '{}': its result is "
                                + "discarded, topics deleted '{}'", run.runId(), run.projectId(), deleted);
                    }
                    return deleted == 0;
                })
                .onErrorResume(error -> {
                    log.warn("Topics run '{}' could not check its project after the save, project '{}'; the labelling "
                            + "pass labels its window", run.runId(), run.projectId(), error);
                    return Mono.just(false);
                });
    }

    /**
     * Inserts {@code trace_topics} rows of a run, for the run itself or its labelling pass, or of topic on arrival. A
     * transient error is retried up to {@code topics.run.insertRetries} times (a repeat writes the same rows, one per
     * trace); any other fails at once. Each attempt stops after {@code topics.run.readTimeout}. Counted by result. No
     * rows, no insert.
     */
    Mono<Void> insertTraceTopics(@NonNull TopicsMetrics.Workspace workspace, @NonNull List<TraceTopic> rows) {
        if (rows.isEmpty()) {
            return Mono.empty();
        }
        var backoff = runConfig.getInsertBackoff().toJavaDuration();
        return traceTopicsDAO.insert(rows)
                .timeout(runConfig.getReadTimeout().toJavaDuration())
                .retryWhen(RetryUtils.handleClickHouseErrors(runConfig.getInsertRetries(), backoff,
                        backoff.multipliedBy(1L << runConfig.getInsertRetries()))
                        .doBeforeRetry(signal -> log.warn(
                                "Topics retries a trace_topics insert, source '{}', run '{}', "
                                        + "project '{}', attempt '{}'",
                                rows.getFirst().source().getValue(),
                                rows.getFirst().runId(), rows.getFirst().projectId(), signal.totalRetries() + 2,
                                signal.failure())))
                .doOnSuccess(__ -> {
                    metrics.saved(workspace, "trace_topics", rows.size());
                    metrics.traceTopicsInsert(workspace, true);
                })
                .doOnError(__ -> metrics.traceTopicsInsert(workspace, false))
                .then();
    }

    /**
     * {@link #insertTraceTopics}, then the rows of traces deleted since the run or its labelling pass read them are
     * deleted ({@link TraceTopicsDAO#deleteRowsOfDeletedTraces}): those reads come minutes before the insert. Topic on
     * arrival writes its rows seconds after it reads the vectors, under its own short timeout, so it inserts without
     * this check.
     */
    Mono<Void> insertTraceTopicsAndDropDeleted(@NonNull TopicsMetrics.Workspace workspace,
            @NonNull List<TraceTopic> rows) {
        if (rows.isEmpty()) {
            return Mono.empty();
        }
        return insertTraceTopics(workspace, rows)
                .then(Mono.defer(() -> traceTopicsDAO.deleteRowsOfDeletedTraces(rows)
                        .doOnNext(deleted -> {
                            if (deleted > 0) {
                                log.info("Deleted the trace_topics rows of traces deleted since they were read, "
                                        + "source '{}', run '{}', workspace '{}', traces '{}'",
                                        rows.getFirst().source().getValue(), rows.getFirst().runId(), workspace.id(),
                                        deleted);
                            }
                        })
                        .then()
                        .onErrorResume(error -> {
                            // The rows hold ids only, every reader takes only traces that exist, and retention
                            // deletes them.
                            log.warn("Topics could not check a trace_topics insert for deleted traces, source '{}', "
                                    + "run '{}', project '{}'", rows.getFirst().source().getValue(),
                                    rows.getFirst().runId(), rows.getFirst().projectId(), error);
                            return Mono.empty();
                        })));
    }

    /** The project's saved list: its row and its topics, read in one transaction so they are of the same save. */
    record SavedList(@NonNull TopicProject project, @NonNull List<Topic> topics) {
    }

    /** The project's saved list, or empty when it has none. */
    Mono<SavedList> readSaved(@NonNull String workspaceId, @NonNull UUID projectId) {
        return jdbi(() -> template.inTransaction(READ_ONLY, handle -> {
            var project = handle.attach(TopicProjectDAO.class).findProjects(workspaceId, List.of(projectId)).stream()
                    .findFirst()
                    .filter(TopicRunStore::hasSavedList);
            return project.map(found -> new SavedList(found,
                    handle.attach(TopicDAO.class).findByProject(workspaceId, projectId))).orElse(null);
        }));
    }

    /**
     * The saved lists of those of {@code projectIds} that have one, read in one transaction so each is of one save. A
     * list's topics are in the order assignment breaks ties by.
     */
    Mono<List<SavedList>> readSavedLists(@NonNull String workspaceId, @NonNull Collection<UUID> projectIds) {
        if (projectIds.isEmpty()) {
            return Mono.just(List.of());
        }
        return jdbi(() -> template.inTransaction(READ_ONLY, handle -> SavedRows.builder()
                .projects(handle.attach(TopicProjectDAO.class).findProjects(workspaceId, projectIds))
                .topics(handle.attach(TopicDAO.class).findByProjects(workspaceId, projectIds))
                .build()))
                .map(rows -> {
                    var topics = rows.topics().stream().collect(Collectors.groupingBy(Topic::projectId));
                    return rows.projects().stream()
                            .filter(TopicRunStore::hasSavedList)
                            .map(project -> new SavedList(project,
                                    topics.getOrDefault(project.projectId(), List.of())))
                            .toList();
                });
    }

    @Builder(toBuilder = true)
    private record SavedRows(@NonNull List<TopicProject> projects, @NonNull List<Topic> topics) {
    }

    private static boolean hasSavedList(TopicProject project) {
        return project.listRunId() != null && project.windowUntil() != null;
    }

    /**
     * Forgets the project's saved list in one transaction: first the row, a compare-and-set on no run in flight, then
     * the project's topics, only when the row moved. False when the project has no row or a run in flight. Its
     * {@code trace_topics} rows stay: no read uses them while the project has no list, and the new run writes a newer
     * row for each trace it labels. The workspace comes from the Reactor context.
     */
    Mono<Boolean> resetList(@NonNull UUID projectId) {
        return Mono.deferContextual(ctx -> {
            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);
            return jdbi(() -> template.inTransaction(WRITE, handle -> {
                if (handle.attach(TopicProjectDAO.class).resetList(workspaceId, projectId, ENDED) != 1) {
                    return false;
                }
                handle.attach(TopicDAO.class).deleteTopicsByProjects(workspaceId, List.of(projectId));
                return true;
            }));
        });
    }

    /**
     * Sets {@code labels_complete} for the run, with the fingerprint of the labeller the pass labelled by, only while
     * the project still names the run; false when it no longer does.
     */
    Mono<Boolean> markLabelsComplete(@NonNull String workspaceId, @NonNull UUID projectId, @NonNull UUID runId,
            @NonNull String fingerprint) {
        return jdbi(() -> template.inTransaction(WRITE, handle -> handle.attach(TopicProjectDAO.class)
                .markLabelsComplete(workspaceId, projectId, runId, fingerprint)) > 0);
    }

    private <T> Mono<T> jdbi(Callable<T> call) {
        return Mono.fromCallable(call).subscribeOn(Schedulers.boundedElastic());
    }
}

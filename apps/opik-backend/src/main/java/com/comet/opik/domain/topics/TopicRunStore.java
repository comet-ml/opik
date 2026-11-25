package com.comet.opik.domain.topics;

import com.comet.opik.domain.IdGenerator;
import com.comet.opik.domain.ProjectService;
import com.comet.opik.domain.topics.TopicProject.RunError;
import com.comet.opik.infrastructure.TopicsConfig;
import com.comet.opik.utils.RetryUtils;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;

/**
 * A run's MySQL state and its {@code trace_topics} writes: its attempt on the project's {@code topic_projects} row
 * (start, fail), the save transaction, the saved list the labelling pass reads and marks complete, and the
 * {@code trace_topics} insert both the run and the pass use. MySQL calls are blocking JDBI, each wrapped once on a
 * bounded-elastic thread.
 */
@Slf4j
@Singleton
class TopicRunStore {

    private final TopicsConfig.RunConfig runConfig;
    private final TransactionTemplate template;
    private final TraceTopicsDAO traceTopicsDAO;
    private final ProjectService projectService;
    private final IdGenerator idGenerator;
    private final TopicsMetrics metrics;

    @Inject
    TopicRunStore(@NonNull @Config("topics") TopicsConfig config, @NonNull TransactionTemplate template,
            @NonNull TraceTopicsDAO traceTopicsDAO, @NonNull ProjectService projectService,
            @NonNull IdGenerator idGenerator, @NonNull TopicsMetrics metrics) {
        this.runConfig = config.getRun();
        this.template = template;
        this.traceTopicsDAO = traceTopicsDAO;
        this.projectService = projectService;
        this.idGenerator = idGenerator;
        this.metrics = metrics;
    }

    /**
     * Starts a run on the project's {@code topic_projects} row, creating the row when it has none. The caller holds the
     * project's lock. An attempt the row still shows {@code running} and that started before {@code abandonedBefore},
     * the lock's lease ago, lost its pod: it is recorded first, as failed with {@code abandoned}, and counted.
     */
    Mono<UUID> startRun(@NonNull TopicsMetrics.Workspace workspace, @NonNull UUID projectId,
            @NonNull TopicProject.RunMode mode, @NonNull Instant abandonedBefore) {
        var workspaceId = workspace.id();
        return jdbi(() -> {
            var runId = idGenerator.generateId();
            int abandoned = template.inTransaction(WRITE, handle -> {
                var projectDAO = handle.attach(TopicProjectDAO.class);
                var now = Instant.now();
                int recorded = projectDAO.recordAbandoned(workspaceId, projectId, abandonedBefore, now);
                projectDAO.startRun(workspaceId, projectId, runId, mode, now);
                return recorded;
            });
            if (abandoned > 0) {
                log.warn("Topics run had an attempt left running past its lease, recorded as abandoned, project '{}'",
                        projectId);
                metrics.run(workspace, TopicProject.RunStatus.FAILED, RunError.ABANDONED.getValue());
            }
            return runId;
        });
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
     * saves a project that has no list: if another run saved the project anyway (a Redis failover, or a timed-out run
     * that outlives its lock), the save is a conflict. A run failed meanwhile, by its timeout, rolls the save back.
     *
     * <p>The project's row is not locked. After the commit the save reads it: when the project is gone, the run's topics
     * are deleted and the result is false, so the caller skips the {@code trace_topics} insert. A delete that commits
     * after that read leaves the topics to the discovery job's sweep of deleted projects.
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
     * ({@link TopicProjectDAO#resetList}). A run failed meanwhile, by its timeout, rolls the save back.
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
                        : projectDAO.resetList(run.workspaceId(), run.projectId(), saved.listRunId(), now);
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
                .flatMap(__ -> keepIfProjectExists(run))
                .map(exists -> exists && save.keep());
    }

    /** False, after deleting the run's topics, when the project was deleted during the run. */
    private Mono<Boolean> keepIfProjectExists(TopicRun run) {
        return jdbi(() -> {
            boolean exists = !projectService.findByIds(run.workspaceId(), Set.of(run.projectId())).isEmpty();
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

    /**
     * Inserts {@code trace_topics} rows of a run, for the run itself or its labelling pass. A transient error is
     * retried up to {@code topics.run.insertRetries} times (a repeat writes the same rows, one per trace); any other
     * fails at once. Each attempt stops after {@code topics.run.readTimeout}. Counted by result. No rows, no insert.
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
                        .doBeforeRetry(signal -> log.warn("Topics retries a trace_topics insert, run '{}', project "
                                + "'{}', attempt '{}'", rows.getFirst().runId(), rows.getFirst().projectId(),
                                signal.totalRetries() + 2, signal.failure())))
                .doOnSuccess(__ -> {
                    metrics.saved(workspace, "trace_topics", rows.size());
                    metrics.traceTopicsInsert(workspace, true);
                })
                .doOnError(__ -> metrics.traceTopicsInsert(workspace, false))
                .then();
    }

    /** The project's saved list: its row and its topics, read in one transaction so they are of the same save. */
    record SavedList(@NonNull TopicProject project, @NonNull List<Topic> topics) {
    }

    /** The project's saved list, or empty when it has none. */
    Mono<SavedList> readSaved(@NonNull String workspaceId, @NonNull UUID projectId) {
        return jdbi(() -> template.inTransaction(READ_ONLY, handle -> {
            var project = handle.attach(TopicProjectDAO.class).findProjects(workspaceId, List.of(projectId)).stream()
                    .findFirst()
                    .filter(found -> found.listRunId() != null && found.windowUntil() != null);
            return project.map(found -> new SavedList(found,
                    handle.attach(TopicDAO.class).findByProject(workspaceId, projectId))).orElse(null);
        }));
    }

    /** Sets {@code labels_complete} for the run, only while the project still names it; false when it no longer does. */
    Mono<Boolean> markLabelsComplete(@NonNull String workspaceId, @NonNull UUID projectId, @NonNull UUID runId) {
        return jdbi(() -> template.inTransaction(WRITE, handle -> handle.attach(TopicProjectDAO.class)
                .markLabelsComplete(workspaceId, projectId, runId)) > 0);
    }

    private <T> Mono<T> jdbi(Callable<T> call) {
        return Mono.fromCallable(call).subscribeOn(Schedulers.boundedElastic());
    }
}

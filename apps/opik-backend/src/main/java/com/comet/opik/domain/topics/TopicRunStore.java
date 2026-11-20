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
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.time.Duration;
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
 * A run's writes: its attempt on the project's {@code topic_projects} row (start, fail), the save transaction and the
 * {@code trace_topics} insert after it. MySQL calls are blocking JDBI, each wrapped once on a bounded-elastic thread.
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
    private final Scheduler jdbi = Schedulers.boundedElastic();

    @Inject
    TopicRunStore(@NonNull @Config("topics") TopicsConfig config, @NonNull TransactionTemplate template,
            @NonNull TraceTopicsDAO traceTopicsDAO, @NonNull ProjectService projectService,
            @NonNull IdGenerator idGenerator, @NonNull TopicsMetrics metrics) {
        this.build = config.getBuild();
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
                var runDAO = handle.attach(TopicProjectDAO.class);
                var now = Instant.now();
                int recorded = runDAO.recordAbandoned(workspaceId, projectId, abandonedBefore, now);
                runDAO.startRun(workspaceId, projectId, runId, mode, now);
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
     * and the run as {@code saved}. The list is a compare-and-set on an empty {@code list_run_id}, as a first build
     * saves a project that has no list: if another run saved the project anyway (a Redis failover, or a timed-out run
     * that outlives its lock), the save is a conflict. A run failed meanwhile, by its timeout, rolls the save back.
     *
     * <p>The project's row is not locked. After the commit the save reads it: when the project is gone, the run's topics
     * are deleted and the result is false, so the caller skips the {@code trace_topics} insert. A delete that commits
     * after that read leaves the topics to the first-build job's sweep of deleted projects.
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

    /** The list a nightly update read: its state, the run that saved it, and the ids of its stored topics. */
    record SavedList(@NonNull NightState state, @NonNull UUID listRunId, @NonNull Set<UUID> topicIds) {
    }

    /**
     * What a nightly update writes to MySQL. {@code keep} is false when no topic stayed live: then the list is cleared
     * and every stored topic is in {@code retired}.
     */
    record NightSave(boolean keep, @NonNull List<Topic> inserted, @NonNull List<Topic> updated,
            @NonNull List<UUID> retired, PrototypeVectors otherVectors) {
    }

    /**
     * The saved list as the night reads it, in one transaction: the topics in list order (born, then id) with their
     * vectors, the known-Other vectors, and the run that saved it, which the save checks is still the saved one.
     * Fails with {@code internal} when the project has no saved list.
     */
    Mono<SavedList> readList(@NonNull TopicRun run, @NonNull LocalDate night) {
        return jdbi(() -> template.inTransaction(READ_ONLY, handle -> {
            var project = handle.attach(TopicProjectDAO.class)
                    .findProjects(run.workspaceId(), List.of(run.projectId())).stream().findFirst();
            var topics = handle.attach(TopicDAO.class).findByProject(run.workspaceId(), run.projectId());
            if (project.isEmpty() || project.get().listRunId() == null || topics.isEmpty()) {
                throw new TopicsRunException(RunError.INTERNAL, "the project has no saved topic list");
            }
            var otherVectors = project.get().otherVectors();
            var state = NightState.builder()
                    .topics(topics.stream().map(TopicRunStore::nightTopic).toList())
                    .otherPrototypes(otherVectors == null ? List.of() : otherVectors.vectors())
                    .rebuilds(project.get().savedRuns())
                    .night(night)
                    .ids(idGenerator::generateId)
                    .build();
            return new SavedList(state, project.get().listRunId(),
                    topics.stream().map(Topic::id).collect(Collectors.toUnmodifiableSet()));
        }));
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
    Mono<Boolean> saveUpdate(@NonNull TopicRun run, @NonNull SavedList saved, @NonNull NightSave save) {
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

    /** The longest the insert after the commit takes: every attempt to its stop, the backoffs, and some slack. */
    static Duration insertBound(@NonNull TopicsConfig.BuildConfig build) {
        int retries = build.getInsertRetries();
        var backoffs = build.getInsertBackoff().toJavaDuration().multipliedBy((1L << retries) - 1);
        return build.getReadTimeout().toJavaDuration().multipliedBy(retries + 1L).plus(backoffs)
                .plus(INSERT_BOUND_SLACK);
    }

    /**
     * Inserts the saved run's trace topics. A transient error is retried up to {@code topics.build.insertRetries}
     * times (a repeat writes the same rows, one per trace); any other fails at once. Counted ok or failed. Each attempt stops after {@code topics.build.readTimeout}. False when it still failed:
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
                    metrics.traceTopicsInsert(run.workspace(), "ok");
                    return true;
                })
                .onErrorResume(exception -> {
                    metrics.traceTopicsInsert(run.workspace(), "failed");
                    log.error("Topics run '{}' saved, but its trace_topics insert failed, project '{}'; the labelling "
                            + "pass gives the window's traces their topics", run.runId(), run.projectId(), exception);
                    return Mono.just(false);
                });
    }

    private <T> Mono<T> jdbi(Callable<T> call) {
        return Mono.fromCallable(call).subscribeOn(jdbi);
    }
}

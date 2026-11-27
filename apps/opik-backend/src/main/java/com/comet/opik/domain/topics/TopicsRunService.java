package com.comet.opik.domain.topics;

import com.comet.opik.domain.IdGenerator;
import com.comet.opik.domain.topics.TopicProject.RunError;
import com.comet.opik.infrastructure.TopicsConfig;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.function.Function;

/**
 * One project's discovery run, as one reactive flow: read the window ({@link TopicWindowReader}), cluster it, make the
 * author call ({@link TopicsLlm}), finish the list into its rows ({@link TopicRunRows}) and save ({@link TopicRunStore}).
 * It also drives a nightly update's steps ({@link #update}). It owns the run's time limit and its cancel. Its CPU
 * steps, clustering, assignment and a nightly update's steps, run on {@code parallel()}, off the ClickHouse driver's and
 * the inference client's threads that emit their inputs.
 *
 * <p>The save commits one MySQL transaction first: the list on the project's {@code topic_projects} row, the new topics
 * with their prototype vectors, and the run as {@code saved}. The run's time limit ends at that commit. Then it inserts
 * {@code trace_topics} for the traces it read, with its own bound. A failure before the commit writes nothing but the
 * failed attempt. The topics' sizes and the run's Other share are those of the traces it read: on a sampled window, of
 * the sample. The run's stats go to its log lines, not to a table.
 *
 * <p>A nightly update ({@link #nightlyUpdate}) reads the same window and the project's saved list, has the algorithm
 * update the list and saves the changed list the same way: one transaction that checks no other run saved the project
 * since this one read it, then the {@code trace_topics} insert. A retired topic's row is deleted, with a log line and
 * {@code topics.topics.retired}. A save that leaves no live topic deletes them all and clears the project's list instead, so
 * a later discovery run makes a new one.
 */
@Slf4j
@Singleton
public class TopicsRunService {

    private final @NonNull TopicsConfig config;
    private final @NonNull TopicWindowReader reader;
    private final @NonNull TopicsLlm llm;
    private final @NonNull TopicRunStore store;
    private final @NonNull TopicRunOutcomes outcomes;
    private final @NonNull IdGenerator idGenerator;
    private final @NonNull TopicsMetrics metrics;
    private final @NonNull TopicAlgorithm algorithm;

    @Inject
    TopicsRunService(@NonNull @Config("topics") TopicsConfig config, @NonNull TopicWindowReader reader,
            @NonNull TopicsLlm llm, @NonNull TopicRunStore store, @NonNull TopicRunOutcomes outcomes,
            @NonNull IdGenerator idGenerator, @NonNull TopicsMetrics metrics, @NonNull TopicAlgorithm algorithm) {
        this.config = config;
        this.reader = reader;
        this.llm = llm;
        this.store = store;
        this.outcomes = outcomes;
        this.idGenerator = idGenerator;
        this.metrics = metrics;
        this.algorithm = algorithm;
    }

    /**
     * Makes the project's first topic list for {@code night}, the UTC date whose start ends the window, which the
     * caller counted as {@code windowTraces} traces, in the workspace of the Reactor context. The save leaves the
     * labels incomplete. Never errors: a failure is recorded on the run and returned. A run that outlasts {@code
     * topics.run.projectTimeout} before its commit fails with {@code timeout}; one cancelled by its subscriber is
     * failed with {@code timeout} too.
     */
    Mono<TopicRunOutcome> discover(@NonNull UUID runId, @NonNull UUID projectId, @NonNull LocalDate night,
            long windowTraces) {
        return asRun(runId, projectId, night, run -> run(run, windowTraces));
    }

    /**
     * Updates the project's saved topic list for {@code night}, the UTC date whose start ends the window, which the
     * caller counted as {@code windowTraces} traces, in the workspace of the Reactor context: the window read, the
     * saved list read ({@link TopicRunStore#readList}), the algorithm's steps ({@link #update}), the save
     * ({@link TopicRunStore#saveUpdate}), then the {@code trace_topics} insert. Never errors: a failure is recorded on
     * the run and returned, and the previous list stays. The same time limit and cancel as {@link #discover}. A save
     * that leaves no live topic deletes them all and clears the project's list, so a later discovery run makes a new
     * one; it inserts no {@code trace_topics}.
     */
    Mono<TopicRunOutcome> nightlyUpdate(@NonNull UUID runId, @NonNull UUID projectId, @NonNull LocalDate night,
            long windowTraces) {
        return asRun(runId, projectId, night, run -> nightly(run, night, windowTraces));
    }

    /** Runs {@code work} as the claimed run, in the workspace of the Reactor context; a cancel fails it as timeout. */
    private Mono<TopicRunOutcome> asRun(UUID runId, UUID projectId, LocalDate night,
            Function<TopicRun, Mono<TopicRunOutcome>> work) {
        var runs = TopicsMetrics.Workspace.fromContext().map(workspace -> TopicRun.of(runId, workspace.id(),
                workspace.name(), projectId, night, config.getRun().getWindowDays()));
        return Mono.usingWhen(runs, work, started -> Mono.empty(), (started, error) -> Mono.empty(),
                outcomes::cancelled);
    }

    private Mono<TopicRunOutcome> run(TopicRun run, long windowTraces) {
        int maxTopics = config.getAlgorithm().getMaxTopics();
        var timeout = config.getRun().getProjectTimeout().toJavaDuration();
        // A step's failure carries the stats of the steps before it; a timeout cuts the chain from outside, so it
        // carries the bounds, and the phase metric shows how far the run got.
        return timed(run, "window", reader.read(run, windowTraces))
                // A Flux flatMap lets go of the completed cluster step, and of the window it captured, before the
                // author call.
                .flux().flatMap(window -> cpu(run, "cluster", () -> cluster(window, maxTopics))).next()
                .flatMap(clustered -> timed(run, "llm",
                        llm.generate(run, clustered.clustering().author(), AuthorAnswer::parse))
                        .flatMap(answer -> cpu(run, "assign", () -> assign(run, clustered, answer)))
                        .onErrorMap(error -> TopicsRunException.withStats(error, clustered.clustering().stats()))
                        .onErrorMap(error -> TopicsRunException.withStats(error, clustered.windowStats())))
                .flatMap(rows -> timed(run, "save", store.save(run, rows.topics(), rows.otherVectors()))
                        .map(kept -> kept ? rows : rows.discarded())
                        .onErrorMap(error -> TopicsRunException.withStats(error, rows.stats())))
                .timeout(timeout, Mono.error(this::timedOut))
                .flatMap(rows -> saved(run, rows))
                .onErrorResume(error -> outcomes.failed(run, error));
    }

    /**
     * The window's groups, with what the run needs of the window after them: its traces and stats. The window's error
     * mapping is here, as a lambda capturing the window would keep it through the author call.
     */
    private Clustered cluster(RunWindow window, int maxTopics) {
        try {
            return new Clustered(window.traces(), window.stats(), algorithm.cluster(window.topicWindow(), maxTopics));
        } catch (RuntimeException exception) {
            throw TopicsRunException.withStats(exception, window.stats());
        }
    }

    private record Clustered(List<WindowTrace> traces, ObjectNode windowStats, Clustering clustering) {
    }

    /** The list from the answer, as the rows the save writes, with the run's stats; a broken list is an invariant. */
    private TopicRunRows assign(TopicRun run, Clustered clustered, TopicsLlm.Answer<AuthorAnswer> answer) {
        var traces = clustered.traces();
        var clustering = clustered.clustering();
        TopicRunRows rows;
        try {
            rows = TopicRunRows.of(run, traces, clustering.finish(answer.value()), idGenerator::generateId);
        } catch (IllegalArgumentException exception) {
            throw new TopicsRunException(RunError.INVARIANT, exception.getMessage(), exception);
        }
        var stats = TopicRunOutcomes.bounds(run);
        stats.setAll(clustered.windowStats());
        stats.setAll(clustering.stats());
        stats.setAll(answer.stats());
        stats.setAll(rows.stats());
        log.info("Topics run '{}' built, project '{}', window ['{}', '{}'), vectors '{}', topic sizes '{}', Other "
                + "share '{}'", run.runId(), run.projectId(), run.from(), run.to(), traces.size(),
                rows.topics().stream().map(Topic::size).toList(), TopicText.fixed(rows.otherShare(), 3));
        return rows.toBuilder().stats(stats).build();
    }

    private Mono<TopicRunOutcome> nightly(TopicRun run, LocalDate night, long windowTraces) {
        var timeout = config.getRun().getProjectTimeout().toJavaDuration();
        return timed(run, "window", reader.read(run, windowTraces))
                .flatMap(window -> timed(run, "state", store.readList(run, night))
                        .flatMap(list -> {
                            // The update changes the state in place.
                            int topicsBefore = list.state().liveCount();
                            return update(run, list.state(), nightWindow(run, window))
                                    .flatMap(result -> cpu(run, "rows",
                                            () -> rows(run, window, list, topicsBefore, result)));
                        })
                        .onErrorMap(error -> TopicsRunException.withStats(error, window.stats())))
                .flatMap(updated -> timed(run, "save", store.saveUpdate(run, updated.list(), updated.save()))
                        .map(kept -> kept ? updated : updated.discarded())
                        .onErrorMap(error -> TopicsRunException.withStats(error, updated.stats())))
                .timeout(timeout, Mono.error(this::timedOut))
                .flatMap(updated -> savedNight(run, updated))
                .onErrorResume(error -> outcomes.failed(run, error));
    }

    private static NightWindow nightWindow(TopicRun run, RunWindow window) {
        var createdAt = new Instant[window.texts().length];
        for (int i = 0; i < createdAt.length; i++) {
            createdAt[i] = window.traces().get(i).traceCreatedAt();
        }
        return NightWindow.builder().texts(window.texts()).vectors(window.vectors()).createdAt(createdAt)
                .from(run.from()).night(run.to()).dimensions(TopicEmbedding.DIMENSIONS).build();
    }

    /** The night's rows, with the run's stats; a state that breaks the night's invariants fails the run. */
    private NightRows rows(TopicRun run, RunWindow window, NightList list, int topicsBefore, TopicUpdate result) {
        NightRows rows;
        try {
            NightInvariants.check(list.state(), TopicEmbedding.DIMENSIONS);
            rows = NightRows.of(run, window.traces(), list, topicsBefore, result);
        } catch (IllegalArgumentException exception) {
            throw new TopicsRunException(RunError.INVARIANT, exception.getMessage(), exception);
        }
        var stats = TopicRunOutcomes.bounds(run);
        stats.setAll(window.stats());
        stats.put("mode", TopicProject.RunMode.UPDATE.getValue());
        stats.setAll(rows.stats());
        log.info("Topics run '{}' updated, project '{}', window ['{}', '{}'), vectors '{}', born '{}', retired '{}', "
                + "topics '{}', Other share '{}', churn '{}'", run.runId(), run.projectId(), run.from(), run.to(),
                window.traces().size(), rows.born(), stats.get("retired"), rows.live(),
                TopicText.fixed(rows.otherShare(), 3), rows.churn());
        return rows.toBuilder().stats(stats).build();
    }

    /**
     * After the commit: the night's metrics, a log line and {@code topics.topics.retired} per retired topic, then the
     * shared post-save step. A cleared list has no {@code trace_topics} to write.
     */
    private Mono<TopicRunOutcome> savedNight(TopicRun run, NightRows night) {
        metrics.topics(run.workspace(), night.live(), night.born());
        for (var topic : night.retired()) {
            // Ids only: a name and definition come from trace text and must not reach the logs.
            log.info("Topics run '{}' retired topic '{}', project '{}', reason '{}', size '{}'", run.runId(),
                    topic.getId(), run.projectId(), topic.getRetiredReason().getValue(), topic.getSize());
            metrics.retired(run.workspace(), topic.getRetiredReason());
        }
        metrics.churn(run.workspace(), night.churn());
        if (!night.save().keep()) {
            log.info("Topics run '{}' saved, project '{}', no topic stayed live: its topics are deleted and its list "
                    + "cleared, so a later discovery run makes a new one", run.runId(), run.projectId());
        }
        return afterSave(run, night.traceTopics(), night.otherShare(), night.stats());
    }

    /**
     * One nightly update of {@code state} over {@code window}, changed in place: the algorithm's steps, and each LLM
     * call they ask for through {@link TopicsLlm}, with that call's deadline. The steps run on {@code parallel()}. An answer that is not a JSON object counts as {@code bad_answer} and reaches the algorithm as null; a
     * failed call errors with its code and ends the night. The result's stats are the night's with its calls' added.
     */
    Mono<TopicUpdate> update(@NonNull TopicRun run, @NonNull NightState state, @NonNull NightWindow window) {
        return cpu(run, "update", () -> algorithm.update(state, window, config.getAlgorithm().getMaxTopics()))
                .flatMap(step -> night(run, step, JsonUtils.createObjectNode()));
    }

    /** Answers each {@link NightStep.Ask} in turn, one call at a time, until the night is done. */
    private Mono<TopicUpdate> night(TopicRun run, NightStep<TopicUpdate> step, ObjectNode calls) {
        return switch (step) {
            case NightStep.Done<TopicUpdate> done -> {
                var stats = calls.deepCopy();
                stats.setAll(done.result().stats());
                yield Mono.just(done.result().toBuilder().stats(stats).build());
            }
            case NightStep.Ask<TopicUpdate> ask ->
                timed(run, "llm", llm.generate(run, ask.request(), NightJson::object))
                        .flatMap(answer -> cpu(run, "update", () -> ask.next().apply(answer.value()))
                                .flatMap(next -> night(run, next, plus(calls, answer.stats()))));
        };
    }

    /**
     * The calls' stats with one more call's: numbers add up, and any other field takes the last call's value, as a
     * nightly update makes several calls.
     */
    private static ObjectNode plus(ObjectNode calls, ObjectNode call) {
        var sum = calls.deepCopy();
        call.properties().forEach(field -> {
            if (field.getValue().isNumber()) {
                sum.put(field.getKey(), sum.path(field.getKey()).asLong(0) + field.getValue().asLong());
            } else {
                sum.set(field.getKey(), field.getValue());
            }
        });
        return sum;
    }

    private Mono<TopicRunOutcome> saved(TopicRun run, TopicRunRows rows) {
        return afterSave(run, rows.traceTopics(), rows.otherShare(), rows.stats());
    }

    /**
     * After a run's commit, for a discovery run and a nightly update alike: the Other share metric, then the
     * {@code trace_topics} insert. A failed insert leaves the run saved: the labelling pass gives the window's traces
     * their topics.
     */
    private Mono<TopicRunOutcome> afterSave(TopicRun run, List<TraceTopic> traceTopics, double otherShare,
            ObjectNode stats) {
        metrics.otherShare(run.workspace(), otherShare);
        return store.insertTraceTopics(run.workspace(), traceTopics)
                .thenReturn(traceTopics.size())
                .onErrorResume(exception -> {
                    log.error("Topics run '{}' saved, but its trace_topics insert failed, project '{}'; the labelling "
                            + "pass gives the window's traces their topics", run.runId(), run.projectId(), exception);
                    return Mono.just(0);
                })
                .map(inserted -> outcomes.saved(run, stats, inserted));
    }

    private TopicsRunException timedOut() {
        return new TopicsRunException(RunError.TIMEOUT, "the run outlasted topics.run.projectTimeout '%s'"
                .formatted(config.getRun().getProjectTimeout()));
    }

    /** A CPU step on {@code parallel()}, off the thread that emitted its input; timed from when it starts there. */
    private <T> Mono<T> cpu(TopicRun run, String phase, Callable<T> step) {
        return timed(run, phase, Mono.fromCallable(step)).subscribeOn(Schedulers.parallel());
    }

    /** Records the phase's duration, monotonic, from its subscription to its end, whatever the end. */
    private <T> Mono<T> timed(TopicRun run, String phase, Mono<T> step) {
        return Mono.defer(() -> {
            long started = System.nanoTime();
            return step.doFinally(
                    signal -> metrics.phase(run.workspace(), phase, Duration.ofNanos(System.nanoTime() - started)));
        });
    }

}

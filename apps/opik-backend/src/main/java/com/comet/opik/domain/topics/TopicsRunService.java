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
import java.util.ArrayList;
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
 * {@code topics.retired}. A save that leaves no live topic deletes them all and clears the project's list instead, so
 * the discovery job builds the project again.
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
     * Builds the project's first topic list for {@code night}, the UTC date whose start ends the window, which the
     * caller counted as {@code windowTraces} traces. The save leaves the labels incomplete. Never errors: a failure is recorded on the run and
     * returned. A run that outlasts {@code topics.run.projectTimeout} before its commit fails with {@code timeout};
     * one cancelled by its subscriber is failed with {@code timeout} too.
     */
    Mono<TopicRunOutcome> discover(@NonNull UUID runId, @NonNull TopicsMetrics.Workspace workspace,
            @NonNull UUID projectId, @NonNull LocalDate night, long windowTraces) {
        return start(runId, workspace, projectId, night, run -> run(run, windowTraces));
    }

    /**
     * Updates the project's saved topic list for {@code night}, the UTC date whose start ends the window, which the
     * caller counted as {@code windowTraces} traces: the window read, the saved list read ({@link TopicRunStore#readList}),
     * the algorithm's steps ({@link #update}), the save ({@link TopicRunStore#saveUpdate}), then the {@code trace_topics}
     * insert. Never errors: a failure is recorded on the run and returned, and the previous list stays. The same time
     * limit and cancel as {@link #discover}. A save that leaves no live topic deletes them all and clears the project's
     * list, so the discovery job builds it again; it inserts no {@code trace_topics}. As for a discovery run, the
     * labelling pass is the caller's.
     */
    Mono<TopicRunOutcome> nightlyUpdate(@NonNull UUID runId, @NonNull TopicsMetrics.Workspace workspace,
            @NonNull UUID projectId, @NonNull LocalDate night, long windowTraces) {
        return start(runId, workspace, projectId, night, run -> nightly(run, night, windowTraces));
    }

    /** Does the run's {@code work}; a run cancelled by its subscriber is failed with {@code timeout}. */
    private Mono<TopicRunOutcome> start(UUID runId, TopicsMetrics.Workspace workspace, UUID projectId,
            LocalDate night, Function<TopicRun, Mono<TopicRunOutcome>> work) {
        var run = TopicRun.of(runId, workspace.id(), workspace.name(), projectId, night,
                config.getRun().getWindowDays());
        return Mono.usingWhen(Mono.just(run), work, started -> Mono.empty(), (started, error) -> Mono.empty(),
                outcomes::cancelled);
    }

    private Mono<TopicRunOutcome> run(TopicRun run, long windowTraces) {
        int maxTopics = config.getAlgorithm().getMaxTopics();
        var timeout = config.getRun().getProjectTimeout().toJavaDuration();
        // A step's failure carries the stats of the steps before it; a timeout cuts the chain from outside, so it
        // carries the bounds, and the phase metric shows how far the run got.
        return timed(run, "window", reader.read(run, windowTraces))
                .flatMap(window -> cpu(run, "cluster",
                        () -> algorithm.cluster(window.topicWindow(), maxTopics))
                        .flatMap(clustering -> timed(run, "llm",
                                llm.generate(run, clustering.author(), AuthorAnswer::parse))
                                .flatMap(answer -> cpu(run, "assign",
                                        () -> assign(run, window, clustering, answer)))
                                .onErrorMap(error -> TopicsRunException.withStats(error, clustering.stats())))
                        .onErrorMap(error -> TopicsRunException.withStats(error, window.stats())))
                .flatMap(rows -> timed(run, "save", store.save(run, rows.topics(), rows.otherVectors()))
                        .map(kept -> kept ? rows : rows.discarded())
                        .onErrorMap(error -> TopicsRunException.withStats(error, rows.stats())))
                .timeout(timeout, Mono.error(this::timedOut))
                .flatMap(rows -> saved(run, rows))
                .onErrorResume(error -> outcomes.failed(run, error));
    }

    /** The list from the answer, as the rows the save writes, with the run's stats; a broken list is an invariant. */
    private TopicRunRows assign(TopicRun run, RunWindow window, Clustering clustering,
            TopicsLlm.Answer<AuthorAnswer> answer) {
        TopicRunRows rows;
        try {
            rows = TopicRunRows.of(run, window.traces(), clustering.finish(answer.value()), idGenerator::generateId);
        } catch (IllegalArgumentException exception) {
            throw new TopicsRunException(RunError.INVARIANT, exception.getMessage(), exception);
        }
        var stats = TopicRunOutcomes.bounds(run);
        stats.setAll(window.stats());
        stats.setAll(clustering.stats());
        stats.setAll(answer.stats());
        stats.setAll(rows.stats());
        log.info("Topics run '{}' built, project '{}', window ['{}', '{}'), vectors '{}', topic sizes '{}', Other "
                + "share '{}'", run.runId(), run.projectId(), run.from(), run.to(), window.texts().length,
                rows.topics().stream().map(Topic::size).toList(), TopicText.fixed(rows.otherShare(), 3));
        return rows.toBuilder().stats(stats).build();
    }

    /** What a nightly update saves, with the counts its metrics carry after the commit and the run's stats. */
    private record Night(TopicRunStore.NightList list, TopicRunStore.NightSave save, List<NightTopic> retired,
            List<TraceTopic> traceTopics, int born, int live, double otherShare, TopicChurn.Churn churn,
            ObjectNode stats) {

        /** Saved, but its project was deleted meanwhile, or no topic stayed: no trace_topics to write. */
        Night discarded() {
            return new Night(list, save, retired, List.of(), born, live, otherShare, churn, stats);
        }
    }

    private Mono<TopicRunOutcome> nightly(TopicRun run, LocalDate night, long windowTraces) {
        var timeout = config.getRun().getProjectTimeout().toJavaDuration();
        return timed(run, "window", reader.read(run, windowTraces))
                .flatMap(window -> timed(run, "state", store.readList(run, night))
                        .flatMap(list -> {
                            // The update changes the state in place.
                            int topicsBefore = list.state().liveCount();
                            return update(run, list.state(), nightWindow(run, window))
                                    .map(result -> nightRows(run, window, list, topicsBefore, result));
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

    /** The night's rows and the counts its log line and metrics carry. */
    private Night nightRows(TopicRun run, RunWindow window, TopicRunStore.NightList list, int topicsBefore,
            TopicUpdate result) {
        var state = list.state();
        NightInvariants.check(state, TopicEmbedding.DIMENSIONS);
        var stats = TopicRunOutcomes.bounds(run);
        stats.setAll(window.stats());
        stats.put("mode", TopicProject.RunMode.UPDATE.getValue());
        stats.put("saved_runs_before", state.getRebuilds());
        stats.put("topics_before", topicsBefore);
        stats.setAll(result.stats());
        var traces = window.traces();
        int n = traces.size();
        boolean keep = state.liveCount() > 0;
        int other = 0;
        var topicIds = new UUID[n];
        for (int i = 0; i < n; i++) {
            int label = result.labels()[i];
            other += label == TopicAlgorithm.OTHER ? 1 : 0;
            topicIds[i] = label == TopicAlgorithm.OTHER ? TraceTopic.OTHER : state.topic(label).getId();
        }
        var traceTopics = keep
                ? TopicRunRows.traceTopics(run.workspaceId(), run.projectId(), run.runId(), traces, topicIds)
                : List.<TraceTopic>of();
        var churn = TopicChurn.of(result.before(), result.labels());
        var inserted = new ArrayList<Topic>();
        var updated = new ArrayList<Topic>();
        var retired = new ArrayList<NightTopic>();
        var retiredStats = stats.putObject("retired");
        for (var topic : state.getTopics()) {
            boolean stored = list.topicIds().contains(topic.getId());
            if (!topic.isLive()) {
                var reason = topic.getRetiredReason().getValue();
                retiredStats.put(reason, retiredStats.path(reason).asInt() + 1);
                if (stored) {
                    retired.add(topic);
                }
                continue;
            }
            var row = Topic.builder()
                    .id(topic.getId())
                    .workspaceId(run.workspaceId())
                    .projectId(run.projectId())
                    .name(topic.getName())
                    .definition(topic.getDefinition())
                    .born(topic.getBorn())
                    .lowNights(topic.getLowNights())
                    .size(topic.getSize())
                    .vectors(new PrototypeVectors(topic.getPrototypes()))
                    .build();
            (stored ? updated : inserted).add(row);
        }
        int born = (int) state.getTopics().stream().filter(topic -> !list.topicIds().contains(topic.getId())).count();
        double otherShare = (double) other / n;
        stats.put("born", born);
        stats.put("topics", state.liveCount());
        stats.put("other_share", otherShare);
        stats.put("other_prototypes", state.getOtherPrototypes().size());
        stats.put("churn", churn.churn());
        stats.put("churn_any", churn.churnAny());
        log.info("Topics run '{}' updated, project '{}', window ['{}', '{}'), vectors '{}', born '{}', retired '{}', "
                + "topics '{}', Other share '{}', churn '{}'", run.runId(), run.projectId(), run.from(), run.to(), n,
                born, retiredStats, state.liveCount(), TopicText.fixed(otherShare, 3), churn);
        var otherVectors = !keep || state.getOtherPrototypes().isEmpty()
                ? null
                : new PrototypeVectors(state.getOtherPrototypes());
        var save = TopicRunStore.NightSave.builder()
                .keep(keep)
                .inserted(inserted)
                .updated(updated)
                .retired(retired.stream().map(NightTopic::getId).toList())
                .otherVectors(otherVectors)
                .build();
        return new Night(list, save, retired, traceTopics, born, state.liveCount(), otherShare, churn, stats);
    }

    /**
     * After the commit: the night's metrics, a log line and {@code topics.retired} per retired topic, then the
     * {@code trace_topics} insert, unless the list was cleared or the project is gone.
     */
    private Mono<TopicRunOutcome> savedNight(TopicRun run, Night night) {
        metrics.topics(run.workspace(), night.live(), night.born());
        for (var topic : night.retired()) {
            // Ids only: a name and definition come from trace text and must not reach the logs.
            log.info("Topics run '{}' retired topic '{}', project '{}', reason '{}', size '{}'", run.runId(),
                    topic.getId(), run.projectId(), topic.getRetiredReason().getValue(), topic.getSize());
            metrics.retired(run.workspace(), topic.getRetiredReason());
        }
        metrics.otherShare(run.workspace(), night.otherShare());
        metrics.churn(run.workspace(), night.churn());
        var outcome = TopicRunOutcome.builder().runId(run.runId()).status(TopicProject.RunStatus.SAVED)
                .stats(night.stats()).build();
        if (!night.save().keep()) {
            log.info("Topics run '{}' saved, project '{}', no topic stayed live: its topics are deleted and its list "
                    + "cleared, so the discovery job builds it again", run.runId(), run.projectId());
            return Mono.just(outcome);
        }
        return store.insertTraceTopics(run.workspace(), night.traceTopics())
                .thenReturn(!night.traceTopics().isEmpty())
                .onErrorResume(exception -> {
                    log.error("Topics run '{}' saved, but its trace_topics insert failed, project '{}'; the labelling "
                            + "pass gives the window's traces their topics", run.runId(), run.projectId(), exception);
                    return Mono.just(false);
                })
                .map(inserted -> {
                    log.info("Topics run '{}' saved, project '{}', trace topics '{}', topics inserted '{}', updated "
                            + "'{}', deleted '{}', stats '{}'", run.runId(), run.projectId(),
                            inserted ? night.traceTopics().size() : 0, night.save().inserted().size(),
                            night.save().updated().size(), night.retired().size(), night.stats());
                    return outcome;
                });
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
        metrics.otherShare(run.workspace(), rows.otherShare());
        return store.insertTraceTopics(run.workspace(), rows.traceTopics())
                .thenReturn(!rows.traceTopics().isEmpty())
                .onErrorResume(exception -> {
                    log.error("Topics run '{}' saved, but its trace_topics insert failed, project '{}'; the labelling "
                            + "pass gives the window's traces their topics", run.runId(), run.projectId(), exception);
                    return Mono.just(false);
                })
                .map(inserted -> outcomes.saved(run, rows, inserted));
    }

    private TopicsRunException timedOut() {
        return new TopicsRunException(RunError.TIMEOUT, "the run outlasted topics.run.projectTimeout '%s'"
                .formatted(config.getRun().getProjectTimeout()));
    }

    /** A CPU step on {@code parallel()}, off the thread that emitted its input; timed from when it starts there. */
    private <T> Mono<T> cpu(TopicRun run, String phase, Callable<T> step) {
        return timed(run, phase, Mono.fromCallable(step).subscribeOn(Schedulers.parallel()));
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

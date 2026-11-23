package com.comet.opik.domain.topics;

import com.comet.opik.domain.IdGenerator;
import com.comet.opik.domain.WorkspaceNameService;
import com.comet.opik.domain.topics.TopicProject.RunError;
import com.comet.opik.infrastructure.TopicsConfig;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.Builder;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.exception.ExceptionUtils;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;

/**
 * One project's first build, as one reactive flow: read the window ({@link TopicWindowReader}), cluster it, make the
 * author call ({@link TopicsLlm}), finish the list, check the prototypes and save ({@link TopicRunStore}). It also drives
 * a nightly update's steps ({@link #update}). The CPU steps run on one small bounded scheduler; the run's time limit is
 * a {@code timeout} that cancels the step in flight.
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
 * the first-build job builds the project again.
 */
@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class TopicsRebuildService {

    /**
     * Failure codes whose exceptions can carry trace-derived text: of the LLM's answer, or the topic names and definitions
     * a failed save statement quotes in its bound arguments.
     */
    private static final Set<RunError> TEXT_CODES = EnumSet.of(RunError.LLM_TIMEOUT, RunError.LLM_ERROR,
            RunError.LLM_BAD_ANSWER, RunError.SAVE);

    private final @NonNull @Config("topics") TopicsConfig config;
    private final @NonNull TopicWindowReader reader;
    private final @NonNull TopicsLlm llm;
    private final @NonNull TopicRunStore store;
    private final @NonNull IdGenerator idGenerator;
    private final @NonNull TopicsMetrics metrics;
    private final @NonNull WorkspaceNameService workspaceNames;
    private final @NonNull TopicAlgorithm algorithm;
    /** The k-means and assign steps: CPU work, one run at a time per pod plus one started by hand. */
    private final Scheduler cpu = Schedulers.newBoundedElastic(2, Integer.MAX_VALUE, "topics-cpu", 60, true);

    /** How a run ended, with the stats it logged. {@code errorCode} is null for a saved run. */
    @Builder(toBuilder = true)
    record Outcome(@NonNull UUID runId, @NonNull TopicProject.RunStatus status, String errorCode,
            JsonNode stats) {
    }

    /** What a nightly update saves, and the counts its metrics carry after the commit. */
    private record Night(TopicRunStore.SavedList saved, TopicRunStore.NightSave save, List<NightTopic> retired,
            List<TraceTopic> traceTopics, int born, int live, TopicChurn.Churn churn) {

        /** Saved, but its project was deleted meanwhile, or no topic stayed: no trace_topics to write. */
        Night discarded() {
            return new Night(saved, save, retired, List.of(), born, live, churn);
        }
    }

    /** What a built run saves. */
    private record Built(List<Topic> topics, PrototypeVectors otherVectors, List<TraceTopic> traceTopics) {

        /** Saved, but its project was deleted meanwhile: nothing more to write. */
        Built discarded() {
            return new Built(topics, otherVectors, List.of());
        }
    }

    /**
     * Builds the project's first topic list for {@code night}, the UTC date whose start ends the window, which the
     * caller counted as {@code windowTraces} traces. The save
     * leaves the labels incomplete. Never errors: a failure is recorded on the run and returned. A run that outlasts
     * {@code topics.build.projectTimeout} before its commit fails with {@code timeout}; one cancelled by its subscriber
     * is failed with {@code timeout} too.
     */
    Mono<Outcome> firstBuild(@NonNull UUID runId, @NonNull String workspaceId, @NonNull UUID projectId,
            @NonNull LocalDate night, long windowTraces) {
        return workspaceName(workspaceId).flatMap(workspaceName -> run(TopicRun.of(runId, workspaceId,
                workspaceName, projectId, night, config.getBuild().getWindowDays()), windowTraces));
    }

    /**
     * Updates the project's saved topic list for {@code night}, the UTC date whose start ends the window, which the
     * caller counted as {@code windowTraces} traces: the window read, the saved list read, the algorithm's steps
     * ({@link #update}), the save ({@link TopicRunStore#saveUpdate}), then the {@code trace_topics} insert. Never
     * errors: a failure is recorded on the run and returned, and the previous list stays. The same time limit and
     * race guard as {@link #firstBuild}. A save that leaves no live topic deletes them all and clears the project's list,
     * so the first-build job builds it again; it inserts no {@code trace_topics}. As for a first build, the labelling
     * pass is the caller's.
     */
    Mono<Outcome> nightlyUpdate(@NonNull UUID runId, @NonNull String workspaceId, @NonNull UUID projectId,
            @NonNull LocalDate night, long windowTraces) {
        return workspaceName(workspaceId).flatMap(workspaceName -> nightly(TopicRun.of(runId, workspaceId,
                workspaceName, projectId, night, config.getBuild().getWindowDays()), night, windowTraces));
    }

    /**
     * The name is a Redis read, or an HTTP call on a miss: once per run, off the reactive threads. It falls back to the
     * id, so the run goes on.
     */
    private Mono<String> workspaceName(String workspaceId) {
        return Mono.fromCallable(() -> workspaceNames.nameOrId(workspaceId))
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorReturn(workspaceId);
    }

    private Mono<Outcome> run(TopicRun run, long windowTraces) {
        return withProjectTimeout(build(run, windowTraces)
                .flatMap(built -> timed(run, "save", store.save(run, built.topics(), built.otherVectors()))
                        .map(kept -> kept ? built : built.discarded())))
                .flatMap(built -> saved(run, built))
                .onErrorResume(error -> failed(run, error))
                .doOnCancel(() -> cancelled(run));
    }

    /** A run's work up to its save, failed with {@code timeout} once it outlasts topics.build.projectTimeout. */
    private <T> Mono<T> withProjectTimeout(Mono<T> work) {
        var timeout = config.getBuild().getProjectTimeout().toJavaDuration();
        return work.timeout(timeout, Mono.error(() -> new TopicsRunException(RunError.TIMEOUT,
                "the run outlasted topics.build.projectTimeout '%s'".formatted(timeout))));
    }

    private Mono<Built> build(TopicRun run, long windowTraces) {
        return timed(run, "window", reader.read(run, windowTraces)).flatMap(window -> {
            int n = window.texts().length;
            var stats = run.stats();
            var versionShares = stats.putObject("task_version_shares");
            window.versions().forEach((version, count) -> versionShares.put(String.valueOf(version),
                    (double) count / n));
            stats.put("vectors", n);
            stats.put("provisional", n < config.getAlgorithm().getProvisionalBelow());
            var traces = window.traces();
            return timed(run, "cluster", cpu(() -> algorithm.cluster(window.topicWindow(),
                    config.getAlgorithm().getMaxTopics())))
                    .doOnNext(clustering -> stats.setAll(clustering.stats()))
                    .flatMap(clustering -> timed(run, "llm", llm.generate(run, clustering.author(),
                            AuthorAnswer::parse))
                            .flatMap(answer -> timed(run, "assign", cpu(() -> clustering.finish(answer)))))
                    .map(built -> built(run, traces, built));
        });
    }

    /**
     * One nightly update of {@code state} over {@code window}, changed in place: the algorithm's pure steps on the CPU
     * scheduler, and each LLM call they ask for through {@link TopicsLlm}, with that call's deadline. An answer that is
     * not a JSON object counts as {@code bad_answer} and reaches the algorithm as null; a failed call errors with its
     * code and ends the night. The night's stats go to the run's.
     */
    Mono<TopicUpdate> update(@NonNull TopicRun run, @NonNull NightState state, @NonNull NightWindow window) {
        return cpu(() -> algorithm.update(state, window, config.getAlgorithm().getMaxTopics()))
                .flatMap(step -> night(run, step))
                .doOnNext(update -> run.stats().setAll(update.stats()));
    }

    private Mono<Outcome> nightly(TopicRun run, LocalDate night, long windowTraces) {
        run.stats().put("mode", TopicProject.RunMode.NIGHTLY_UPDATE.getValue());
        return withProjectTimeout(timed(run, "window", reader.read(run, windowTraces))
                .flatMap(window -> timed(run, "state", store.readList(run, night))
                        .flatMap(saved -> updated(run, window, saved)))
                .flatMap(updated -> timed(run, "save", store.saveUpdate(run, updated.saved(), updated.save()))
                        .map(kept -> kept ? updated : updated.discarded())))
                .flatMap(updated -> savedNight(run, updated))
                .onErrorResume(error -> failed(run, error))
                .doOnCancel(() -> cancelled(run));
    }

    /** Runs the night's steps over the window and turns the changed list into the rows the save writes. */
    private Mono<Night> updated(TopicRun run, TopicWindowReader.Window read, TopicRunStore.SavedList saved) {
        int n = read.texts().length;
        var stats = run.stats();
        var versionShares = stats.putObject("task_version_shares");
        read.versions().forEach((version, count) -> versionShares.put(String.valueOf(version), (double) count / n));
        stats.put("vectors", n);
        var state = saved.state();
        stats.put("saved_runs_before", state.getRebuilds());
        stats.put("topics_before", state.liveCount());
        var createdAt = new Instant[n];
        for (int i = 0; i < n; i++) {
            createdAt[i] = read.traces().get(i).traceCreatedAt();
        }
        var window = NightWindow.builder().texts(read.texts()).vectors(read.vectors()).createdAt(createdAt)
                .from(run.from()).night(run.to()).dimensions(TopicEmbedding.DIMENSIONS).build();
        return timed(run, "update", update(run, state, window))
                .map(result -> {
                    NightInvariants.check(state, TopicEmbedding.DIMENSIONS);
                    return night(run, read.traces(), saved, result);
                });
    }

    /** The night's rows and the counts its log line and metrics carry. */
    private Night night(TopicRun run, List<TopicWindowReader.WindowTrace> traces, TopicRunStore.SavedList saved,
            TopicUpdate result) {
        var state = saved.state();
        var stats = run.stats();
        int n = traces.size();
        boolean keep = state.liveCount() > 0;
        int other = 0;
        var traceTopics = new ArrayList<TraceTopic>(keep ? n : 0);
        for (int i = 0; i < n; i++) {
            int label = result.labels()[i];
            other += label == TopicAlgorithm.OTHER ? 1 : 0;
            if (keep) {
                traceTopics.add(TraceTopic.builder()
                        .workspaceId(run.workspaceId())
                        .projectId(run.projectId())
                        .traceId(traces.get(i).traceId())
                        .traceCreatedAt(traces.get(i).traceCreatedAt())
                        .topicId(label == TopicAlgorithm.OTHER ? TraceTopic.OTHER : state.topic(label).getId())
                        .source(TraceTopic.Source.RUN)
                        .runId(run.runId())
                        .build());
            }
        }
        var churn = TopicChurn.of(result.before(), result.labels());
        var inserted = new ArrayList<Topic>();
        var updated = new ArrayList<Topic>();
        var retired = new ArrayList<NightTopic>();
        var retiredStats = stats.putObject("retired");
        for (var topic : state.getTopics()) {
            boolean stored = saved.topicIds().contains(topic.getId());
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
        int born = (int) state.getTopics().stream().filter(topic -> !saved.topicIds().contains(topic.getId()))
                .count();
        stats.put("born", born);
        stats.put("topics", state.liveCount());
        stats.put("other_share", (double) other / n);
        stats.put("other_prototypes", state.getOtherPrototypes().size());
        stats.put("churn", churn.churn());
        stats.put("churn_any", churn.churnAny());
        stats.set("seconds", run.seconds());
        log.info("Topics run '{}' updated, project '{}', window ['{}', '{}'), vectors '{}', born '{}', retired '{}', "
                + "topics '{}', Other share '{}', churn '{}', seconds '{}'", run.runId(), run.projectId(), run.from(),
                run.to(), n, born, retiredStats, state.liveCount(), TopicText.fixed((double) other / n, 3), churn,
                run.seconds());
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
        return new Night(saved, save, retired, traceTopics, born, state.liveCount(), churn);
    }

    /**
     * After the commit: the night's metrics, a log line and {@code topics.retired} per retired topic, then the
     * {@code trace_topics} insert, unless the list was cleared or the project is gone.
     */
    private Mono<Outcome> savedNight(TopicRun run, Night night) {
        metrics.topics(run.workspace(), night.live(), night.born());
        for (var topic : night.retired()) {
            // Ids only: a name and definition come from trace text and must not reach the logs.
            log.info("Topics run '{}' retired topic '{}', project '{}', reason '{}', size '{}'", run.runId(),
                    topic.getId(), run.projectId(), topic.getRetiredReason().getValue(), topic.getSize());
            metrics.retired(run.workspace(), topic.getRetiredReason());
        }
        metrics.otherShare(run.workspace(), run.stats().path("other_share").asDouble());
        metrics.churn(run.workspace(), night.churn());
        if (!night.save().keep()) {
            log.info("Topics run '{}' saved, project '{}', no topic stayed live: its topics are deleted and its list "
                    + "cleared, so the first-build job builds it again", run.runId(), run.projectId());
            return Mono.just(outcome(run, TopicProject.RunStatus.SAVED, null));
        }
        var insert = night.traceTopics().isEmpty()
                ? Mono.just(false)
                : store.insertTraceTopics(run, night.traceTopics());
        return insert.map(inserted -> {
            log.info("Topics run '{}' saved, project '{}', trace topics '{}', topics inserted '{}', updated '{}', "
                    + "deleted '{}', stats '{}'", run.runId(), run.projectId(),
                    inserted ? night.traceTopics().size() : 0, night.save().inserted().size(),
                    night.save().updated().size(), night.retired().size(), run.stats());
            return outcome(run, TopicProject.RunStatus.SAVED, null);
        });
    }

    /** Answers each {@link NightStep.Ask} in turn, one call at a time, until the night is done. */
    private Mono<TopicUpdate> night(TopicRun run, NightStep<TopicUpdate> step) {
        return switch (step) {
            case NightStep.Done<TopicUpdate> done -> Mono.just(done.result());
            case NightStep.Ask<TopicUpdate> ask ->
                timed(run, "llm", llm.generate(run, ask.request(), NightJson::object))
                        .map(Optional::of)
                        .defaultIfEmpty(Optional.empty())
                        .flatMap(answer -> cpu(() -> ask.next().apply(answer.orElse(null))))
                        .flatMap(next -> night(run, next));
        };
    }

    /** Checks the list and turns it into the rows the save writes. */
    private Built built(TopicRun run, List<TopicWindowReader.WindowTrace> traces, FirstBuild built) {
        var stats = run.stats();
        stats.setAll(built.stats());
        checkPrototypes(built);
        var topics = topics(run, built);
        var otherVectors = built.otherPrototypes().isEmpty() ? null : new PrototypeVectors(built.otherPrototypes());
        int n = built.labels().length;
        int other = 0;
        for (int label : built.labels()) {
            other += label == TopicAlgorithm.OTHER ? 1 : 0;
        }
        stats.put("topics", topics.size());
        stats.put("other_share", (double) other / n);
        stats.put("prototypes", topics.stream().mapToInt(topic -> topic.vectors().size()).sum()
                + built.otherPrototypes().size());
        stats.set("seconds", run.seconds());
        log.info("Topics run '{}' built, project '{}', window ['{}', '{}'), vectors '{}', topic sizes '{}', Other "
                + "share '{}', seconds '{}'", run.runId(), run.projectId(), run.from(), run.to(), n,
                topics.stream().map(Topic::size).toList(), TopicText.fixed((double) other / n, 3), run.seconds());
        var traceTopics = new ArrayList<TraceTopic>(traces.size());
        for (int i = 0; i < traces.size(); i++) {
            int label = built.labels()[i];
            traceTopics.add(TraceTopic.builder()
                    .workspaceId(run.workspaceId())
                    .projectId(run.projectId())
                    .traceId(traces.get(i).traceId())
                    .traceCreatedAt(traces.get(i).traceCreatedAt())
                    .topicId(label == TopicAlgorithm.OTHER ? TraceTopic.OTHER : topics.get(label).id())
                    .source(TraceTopic.Source.RUN)
                    .runId(run.runId())
                    .build());
        }
        return new Built(topics, otherVectors, traceTopics);
    }

    /** Each topic with its size and the prototype vectors it owns, in the run's order. */
    private List<Topic> topics(TopicRun run, FirstBuild built) {
        var sizes = new int[built.topics().size()];
        for (int label : built.labels()) {
            if (label != TopicAlgorithm.OTHER) {
                sizes[label]++;
            }
        }
        var topics = new ArrayList<Topic>(sizes.length);
        for (int t = 0; t < sizes.length; t++) {
            var topic = built.topics().get(t);
            if (topic.prototypes().isEmpty()) {
                throw invariant("topic '%s' owns no prototype".formatted(t));
            }
            topics.add(Topic.builder()
                    .id(idGenerator.generateId())
                    .workspaceId(run.workspaceId())
                    .projectId(run.projectId())
                    .name(topic.name())
                    .definition(topic.definition())
                    .born(run.born())
                    .size(sizes[t])
                    .vectors(new PrototypeVectors(topic.prototypes()))
                    .build());
        }
        return topics;
    }

    /** Before anything is written: every prototype has {@value TopicEmbedding#DIMENSIONS} values and unit length. */
    private void checkPrototypes(FirstBuild built) {
        var all = new ArrayList<float[]>();
        built.topics().forEach(topic -> all.addAll(topic.prototypes()));
        all.addAll(built.otherPrototypes());
        for (int p = 0; p < all.size(); p++) {
            var values = all.get(p);
            if (!PrototypeVectors.isUnit(values)) {
                throw invariant("prototype '%s' has '%s' values and norm '%s'".formatted(p, values.length,
                        PrototypeVectors.norm(values)));
            }
        }
    }

    private Mono<Outcome> saved(TopicRun run, Built built) {
        metrics.otherShare(run.workspace(), run.stats().path("other_share").asDouble());
        var insert = built.traceTopics().isEmpty()
                ? Mono.just(false)
                : store.insertTraceTopics(run, built.traceTopics());
        return insert.map(inserted -> {
            log.info("Topics run '{}' saved, project '{}', trace topics '{}', stats '{}'", run.runId(),
                    run.projectId(), inserted ? built.traceTopics().size() : 0, run.stats());
            return outcome(run, TopicProject.RunStatus.SAVED, null);
        });
    }

    /**
     * Marks the run failed. When it had ended already, a timeout raced its commit: the run is saved, without its
     * trace_topics, which the labelling pass gives.
     */
    private Mono<Outcome> failed(TopicRun run, Throwable error) {
        var code = error instanceof TopicsRunException exception ? exception.getCode() : RunError.INTERNAL;
        run.stats().set("seconds", run.seconds());
        return store.fail(run.runId(), run.workspace(), run.projectId(), code)
                .flatMap(marked -> marked ? Mono.just(false) : store.isSaved(run))
                .onErrorResume(markError -> {
                    log.error("Topics run '{}' could not be marked failed, project '{}'", run.runId(),
                            run.projectId(), markError);
                    return Mono.just(false);
                })
                .map(saved -> {
                    if (saved) {
                        log.warn("Topics run '{}' saved, project '{}', but stopped before its trace_topics insert, "
                                + "code '{}'", run.runId(), run.projectId(), code.getValue());
                        return outcome(run, TopicProject.RunStatus.SAVED, null);
                    }
                    logFailure(run, code, error);
                    return outcome(run, TopicProject.RunStatus.FAILED, code.getValue());
                });
    }

    private void cancelled(TopicRun run) {
        log.warn("Topics run '{}' cancelled, project '{}'", run.runId(), run.projectId());
        store.fail(run.runId(), run.workspace(), run.projectId(), RunError.TIMEOUT).subscribe(
                __ -> {
                },
                error -> log.error("Topics run '{}' could not be marked failed, project '{}'", run.runId(),
                        run.projectId(), error));
    }

    private void logFailure(TopicRun run, RunError code, Throwable error) {
        if (TEXT_CODES.contains(code)) {
            // The answer's length and SHA-256 are logged when it arrives.
            log.warn("Topics run '{}' failed, project '{}', code '{}', errors '{}', stats '{}'", run.runId(),
                    run.projectId(), code.getValue(), ExceptionUtils.getThrowableList(error).stream()
                            .map(TopicsErrors::formatError).toList(),
                    run.stats());
        } else {
            log.warn("Topics run '{}' failed, project '{}', code '{}', stats '{}'", run.runId(), run.projectId(),
                    code.getValue(), run.stats(), error);
        }
    }

    private static Outcome outcome(TopicRun run, TopicProject.RunStatus status, String errorCode) {
        return Outcome.builder().runId(run.runId()).status(status).errorCode(errorCode).stats(run.stats()).build();
    }

    private <T> Mono<T> cpu(Callable<T> step) {
        return Mono.fromCallable(step).subscribeOn(cpu);
    }

    /** A phase timed more than once adds up in the stats. */
    private <T> Mono<T> timed(TopicRun run, String phase, Mono<T> action) {
        return Mono.defer(() -> {
            long started = System.nanoTime();
            return action.doFinally(signal -> {
                var duration = Duration.ofNanos(System.nanoTime() - started);
                run.seconds().put(phase, run.seconds().path(phase).asDouble(0) + duration.toMillis() / 1000.0);
                metrics.phase(run.workspace(), phase, duration);
            });
        });
    }

    private static TopicsRunException invariant(String message) {
        return new TopicsRunException(RunError.INVARIANT, message);
    }
}

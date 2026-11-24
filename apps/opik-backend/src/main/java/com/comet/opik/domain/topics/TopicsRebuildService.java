package com.comet.opik.domain.topics;

import com.comet.opik.domain.IdGenerator;
import com.comet.opik.domain.WorkspaceNameService;
import com.comet.opik.domain.topics.TopicProject.RunError;
import com.comet.opik.infrastructure.TopicsConfig;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;

/**
 * One project's first build, as one reactive flow: read the window ({@link TopicWindowReader}), cluster it, make the
 * author call ({@link TopicsLlm}), finish the list, check the prototypes and save ({@link TopicRunStore}). The CPU
 * steps run on one small bounded scheduler; the run's time limit is a {@code timeout} that cancels the step in flight.
 *
 * <p>The save commits one MySQL transaction first: the list on the project's {@code topic_projects} row, the new topics
 * with their prototype vectors, and the run as {@code saved}. The run's time limit ends at that commit. Then it inserts
 * {@code trace_topics} for the traces it read, with its own bound. A failure before the commit writes nothing but the
 * failed attempt. The topics' sizes and the run's Other share are those of the traces it read: on a sampled window, of
 * the sample. The run's stats go to its log lines, not to a table.
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

    /**
     * The stats a run logs and returns, built here from what each phase returns, and its seconds per phase. Only the
     * run's own flow writes it.
     */
    private record RunStats(ObjectNode stats, ObjectNode seconds) {

        static RunStats of(TopicRun run) {
            var stats = JsonUtils.createObjectNode();
            stats.put("window_from", run.from().toString());
            stats.put("window_until", run.to().toString());
            return new RunStats(stats, JsonUtils.createObjectNode());
        }

        void add(ObjectNode phaseStats) {
            stats.setAll(phaseStats);
        }

        void time(String phase, Duration duration) {
            seconds.put(phase, seconds.path(phase).asDouble(0) + duration.toMillis() / 1000.0);
        }

        ObjectNode json() {
            return stats.deepCopy().set("seconds", seconds.deepCopy());
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
        // The name is a Redis read, or an HTTP call on a miss: once per run, off the reactive threads. It falls back
        // to the id, so the run goes on.
        // A cancel during the lookup fails the started attempt too: the run's metrics then carry the id as the name.
        var workspace = new AtomicReference<>(new TopicsMetrics.Workspace(workspaceId, workspaceId));
        return Mono.fromCallable(() -> workspaceNames.nameOrId(workspaceId))
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorReturn(workspaceId)
                .flatMap(workspaceName -> {
                    var run = TopicRun.of(runId, workspaceId, workspaceName, projectId, night,
                            config.getBuild().getWindowDays());
                    workspace.set(run.workspace());
                    return run(run, windowTraces);
                })
                .doOnCancel(() -> cancelled(runId, workspace.get(), projectId));
    }

    private Mono<Outcome> run(TopicRun run, long windowTraces) {
        var timeout = config.getBuild().getProjectTimeout().toJavaDuration();
        var stats = RunStats.of(run);
        return build(run, stats, windowTraces)
                .flatMap(built -> timed(run, stats, "save", store.save(run, built.topics(), built.otherVectors()))
                        .map(kept -> kept ? built : built.discarded()))
                .timeout(timeout, Mono.error(() -> new TopicsRunException(RunError.TIMEOUT,
                        "the run outlasted topics.build.projectTimeout '%s'".formatted(timeout))))
                .flatMap(built -> saved(run, stats, built))
                .onErrorResume(error -> failed(run, stats, error));
    }

    private Mono<Built> build(TopicRun run, RunStats stats, long windowTraces) {
        return timed(run, stats, "window", reader.read(run, windowTraces)).flatMap(window -> {
            stats.add(window.stats());
            stats.add(windowStats(window));
            var traces = window.traces();
            return timed(run, stats, "cluster", cpu(() -> algorithm.cluster(window.topicWindow(),
                    config.getAlgorithm().getMaxTopics())))
                    .doOnNext(clustering -> stats.add(clustering.stats()))
                    .flatMap(clustering -> timed(run, stats, "llm", llm.generate(run, clustering.author(),
                            AuthorAnswer::parse))
                            .doOnNext(answer -> stats.add(answer.stats()))
                            .flatMap(answer -> timed(run, stats, "assign",
                                    cpu(() -> clustering.finish(answer.value())))))
                    .map(built -> built(run, stats, traces, built));
        });
    }

    private ObjectNode windowStats(TopicWindowReader.Window window) {
        int n = window.texts().length;
        var stats = JsonUtils.createObjectNode();
        var versionShares = stats.putObject("task_version_shares");
        window.versions().forEach((version, count) -> versionShares.put(String.valueOf(version),
                (double) count / n));
        stats.put("vectors", n);
        stats.put("provisional", n < config.getAlgorithm().getProvisionalBelow());
        return stats;
    }

    /** Checks the list and turns it into the rows the save writes. */
    private Built built(TopicRun run, RunStats runStats, List<TopicWindowReader.WindowTrace> traces,
            FirstBuild built) {
        checkPrototypes(built);
        var topics = topics(run, built);
        var otherVectors = built.otherPrototypes().isEmpty() ? null : new PrototypeVectors(built.otherPrototypes());
        int n = built.labels().length;
        int other = 0;
        for (int label : built.labels()) {
            other += label == TopicAlgorithm.OTHER ? 1 : 0;
        }
        var stats = built.stats().deepCopy();
        stats.put("topics", topics.size());
        stats.put("other_share", (double) other / n);
        stats.put("prototypes", topics.stream().mapToInt(topic -> topic.vectors().size()).sum()
                + built.otherPrototypes().size());
        runStats.add(stats);
        log.info("Topics run '{}' built, project '{}', window ['{}', '{}'), vectors '{}', topic sizes '{}', Other "
                + "share '{}', seconds '{}'", run.runId(), run.projectId(), run.from(), run.to(), n,
                topics.stream().map(Topic::size).toList(), TopicText.fixed((double) other / n, 3),
                runStats.seconds());
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

    private Mono<Outcome> saved(TopicRun run, RunStats stats, Built built) {
        metrics.otherShare(run.workspace(), stats.stats().path("other_share").asDouble());
        var insert = built.traceTopics().isEmpty()
                ? Mono.just(false)
                : store.insertTraceTopics(run, built.traceTopics());
        return insert.map(inserted -> {
            log.info("Topics run '{}' saved, project '{}', trace topics '{}', stats '{}'", run.runId(),
                    run.projectId(), inserted ? built.traceTopics().size() : 0, stats.json());
            return outcome(run, stats, TopicProject.RunStatus.SAVED, null);
        });
    }

    /**
     * Marks the run failed. When it had ended already, a timeout raced its commit: the run is saved, without its
     * trace_topics, which the labelling pass gives.
     */
    private Mono<Outcome> failed(TopicRun run, RunStats stats, Throwable error) {
        var code = error instanceof TopicsRunException exception ? exception.getCode() : RunError.INTERNAL;
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
                        return outcome(run, stats, TopicProject.RunStatus.SAVED, null);
                    }
                    logFailure(run, stats, code, error);
                    return outcome(run, stats, TopicProject.RunStatus.FAILED, code.getValue());
                });
    }

    private void cancelled(UUID runId, TopicsMetrics.Workspace workspace, UUID projectId) {
        log.warn("Topics run '{}' cancelled, project '{}'", runId, projectId);
        store.fail(runId, workspace, projectId, RunError.TIMEOUT).subscribe(
                __ -> {
                },
                error -> log.error("Topics run '{}' could not be marked failed, project '{}'", runId, projectId,
                        error));
    }

    private void logFailure(TopicRun run, RunStats stats, RunError code, Throwable error) {
        if (TEXT_CODES.contains(code)) {
            // The answer's length and SHA-256 are logged when it arrives.
            log.warn("Topics run '{}' failed, project '{}', code '{}', errors '{}', stats '{}'", run.runId(),
                    run.projectId(), code.getValue(), ExceptionUtils.getThrowableList(error).stream()
                            .map(TopicsErrors::formatError).toList(),
                    stats.json());
        } else {
            log.warn("Topics run '{}' failed, project '{}', code '{}', stats '{}'", run.runId(), run.projectId(),
                    code.getValue(), stats.json(), error);
        }
    }

    private static Outcome outcome(TopicRun run, RunStats stats, TopicProject.RunStatus status, String errorCode) {
        return Outcome.builder().runId(run.runId()).status(status).errorCode(errorCode).stats(stats.json()).build();
    }

    private <T> Mono<T> cpu(Callable<T> step) {
        return Mono.fromCallable(step).subscribeOn(cpu);
    }

    /** A phase timed more than once adds up in the stats. */
    private <T> Mono<T> timed(TopicRun run, RunStats stats, String phase, Mono<T> action) {
        return Mono.defer(() -> {
            long started = System.nanoTime();
            return action.doFinally(signal -> {
                var duration = Duration.ofNanos(System.nanoTime() - started);
                stats.time(phase, duration);
                metrics.phase(run.workspace(), phase, duration);
            });
        });
    }

    private static TopicsRunException invariant(String message) {
        return new TopicsRunException(RunError.INVARIANT, message);
    }
}

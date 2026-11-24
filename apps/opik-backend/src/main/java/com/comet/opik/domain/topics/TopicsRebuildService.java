package com.comet.opik.domain.topics;

import com.comet.opik.domain.IdGenerator;
import com.comet.opik.domain.WorkspaceNameService;
import com.comet.opik.domain.topics.TopicProject.RunError;
import com.comet.opik.infrastructure.TopicsConfig;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.dropwizard.lifecycle.Managed;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.Builder;
import lombok.NonNull;
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
import java.util.concurrent.TimeUnit;
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
public class TopicsRebuildService implements Managed {

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
    /**
     * The k-means and assign steps: CPU work, {@code topics.build.cpuThreads} at a time, at most
     * about {@code topics.build.cpuQueue} waiting. Reactor caps the queue of each thread, so each gets its share,
     * rounded up. Disposed when the application stops.
     */
    private final @NonNull Scheduler cpu;

    @Inject
    TopicsRebuildService(@NonNull @Config("topics") TopicsConfig config, @NonNull TopicWindowReader reader,
            @NonNull TopicsLlm llm, @NonNull TopicRunStore store, @NonNull IdGenerator idGenerator,
            @NonNull TopicsMetrics metrics, @NonNull WorkspaceNameService workspaceNames,
            @NonNull TopicAlgorithm algorithm) {
        this.config = config;
        this.reader = reader;
        this.llm = llm;
        this.store = store;
        this.idGenerator = idGenerator;
        this.metrics = metrics;
        this.workspaceNames = workspaceNames;
        this.algorithm = algorithm;
        var build = config.getBuild();
        int queuePerThread = Math.ceilDiv(build.getCpuQueue(), build.getCpuThreads());
        this.cpu = Schedulers.newBoundedElastic(build.getCpuThreads(), queuePerThread, "topics-cpu", 60, true);
    }

    @Override
    public void stop() {
        cpu.dispose();
    }

    /** How a run ended, with the stats it logged. {@code errorCode} is null for a saved run. */
    @Builder(toBuilder = true)
    record Outcome(@NonNull UUID runId, @NonNull TopicProject.RunStatus status, String errorCode,
            JsonNode stats) {
    }

    /**
     * The stats a run logs and returns, from what each phase returned, and its seconds per phase. Never changed in
     * place: each step passes on a new one.
     */
    private record RunStats(ObjectNode stats, ObjectNode seconds) {

        static RunStats of(TopicRun run) {
            var stats = JsonUtils.createObjectNode();
            stats.put("window_from", run.from().toString());
            stats.put("window_until", run.to().toString());
            return new RunStats(stats, JsonUtils.createObjectNode());
        }

        RunStats with(ObjectNode phaseStats) {
            var next = stats.deepCopy();
            next.setAll(phaseStats);
            return new RunStats(next, seconds);
        }

        /** A phase timed more than once adds up. */
        RunStats timed(String phase, Duration duration) {
            var next = seconds.deepCopy();
            next.put(phase, next.path(phase).asDouble(0) + duration.toMillis() / 1000.0);
            return new RunStats(stats, next);
        }

        ObjectNode json() {
            return stats.deepCopy().set("seconds", seconds.deepCopy());
        }
    }

    /** A step's value, with the run's stats up to and including it. */
    private record Step<T>(T value, RunStats stats) {
    }

    /** A failed step, with the run's stats up to and including it, so the failed outcome logs them. */
    private static final class StepFailure extends RuntimeException {

        private final transient RunStats stats;

        StepFailure(RunStats stats, Throwable cause) {
            super(cause.getMessage(), cause, false, false);
            this.stats = stats;
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
        // topics.build.projectTimeout bounds the steps up to the save; each step gets what is left of it.
        long deadline = Schedulers.parallel().now(TimeUnit.MILLISECONDS)
                + config.getBuild().getProjectTimeout().toMilliseconds();
        return build(run, deadline, RunStats.of(run), windowTraces)
                .flatMap(buildStep -> timed(run, deadline, buildStep.stats(), "save",
                        store.save(run, buildStep.value().topics(), buildStep.value().otherVectors()))
                        .map(kept -> new Step<>(kept.value() ? buildStep.value() : buildStep.value().discarded(),
                                kept.stats())))
                .flatMap(saveStep -> saved(run, saveStep.stats(), saveStep.value()))
                .onErrorResume(error -> failed(run, error));
    }

    private Mono<Step<Built>> build(TopicRun run, long deadline, RunStats start, long windowTraces) {
        return timed(run, deadline, start, "window", reader.read(run, windowTraces)).flatMap(read -> {
            var window = read.value();
            var windowed = read.stats().with(window.stats()).with(windowStats(window));
            return timed(run, deadline, windowed, "cluster", cpu(() -> algorithm.cluster(window.topicWindow(),
                    config.getAlgorithm().getMaxTopics())))
                    .flatMap(clustered -> {
                        var clustering = clustered.value();
                        return timed(run, deadline, clustered.stats().with(clustering.stats()), "llm",
                                llm.generate(run, clustering.author(), AuthorAnswer::parse))
                                .flatMap(answered -> timed(run, deadline,
                                        answered.stats().with(answered.value().stats()), "assign",
                                        cpu(() -> clustering.finish(answered.value().value()))));
                    })
                    .flatMap(assigned -> step(assigned.stats(),
                            () -> built(run, assigned.stats(), window.traces(), assigned.value())));
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

    /** Checks the list and turns it into the rows the save writes, with the run's stats so far plus its own. */
    private Step<Built> built(TopicRun run, RunStats runStats, List<TopicWindowReader.WindowTrace> traces,
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
        var withBuilt = runStats.with(stats);
        log.info("Topics run '{}' built, project '{}', window ['{}', '{}'), vectors '{}', topic sizes '{}', Other "
                + "share '{}', seconds '{}'", run.runId(), run.projectId(), run.from(), run.to(), n,
                topics.stream().map(Topic::size).toList(), TopicText.fixed((double) other / n, 3),
                withBuilt.seconds());
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
        return new Step<>(new Built(topics, otherVectors, traceTopics), withBuilt);
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
        return failingWith(stats, insert).map(inserted -> {
            log.info("Topics run '{}' saved, project '{}', trace topics '{}', stats '{}'", run.runId(),
                    run.projectId(), inserted ? built.traceTopics().size() : 0, stats.json());
            return outcome(run, stats, TopicProject.RunStatus.SAVED, null);
        });
    }

    /**
     * Marks the run failed. When it had ended already, a timeout raced its commit: the run is saved, without its
     * trace_topics, which the labelling pass gives.
     */
    private Mono<Outcome> failed(TopicRun run, Throwable failure) {
        // A step's failure, the timeout included, carries the stats up to it; a cancel comes from outside the steps.
        var stats = failure instanceof StepFailure step ? step.stats : RunStats.of(run);
        var error = failure instanceof StepFailure ? failure.getCause() : failure;
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

    /** The phase's value with {@code stats} plus its duration; a failure carries them too. */
    /** Fails with {@code timeout} when {@code deadline}, in the scheduler's milliseconds, passes first. */
    private <T> Mono<Step<T>> timed(TopicRun run, long deadline, RunStats stats, String phase, Mono<T> action) {
        return Mono.defer(() -> {
            long started = System.nanoTime();
            var left = Duration.ofMillis(deadline - Schedulers.parallel().now(TimeUnit.MILLISECONDS));
            var bounded = left.isPositive()
                    ? action.timeout(left, Mono.error(this::timedOut))
                    : Mono.<T>error(timedOut());
            return failingWith(stats, bounded)
                    .map(value -> new Step<>(value, stats.timed(phase, record(run, phase, started))))
                    .onErrorMap(StepFailure.class,
                            error -> new StepFailure(error.stats.timed(phase, record(run, phase, started)),
                                    error.getCause()))
                    .doOnCancel(() -> record(run, phase, started));
        });
    }

    private TopicsRunException timedOut() {
        return new TopicsRunException(RunError.TIMEOUT, "the run outlasted topics.build.projectTimeout '%s'"
                .formatted(config.getBuild().getProjectTimeout()));
    }

    private Duration record(TopicRun run, String phase, long started) {
        var duration = Duration.ofNanos(System.nanoTime() - started);
        metrics.phase(run.workspace(), phase, duration);
        return duration;
    }

    /** A step that is no phase of its own, such as the checks before the save. */
    private static <T> Mono<Step<T>> step(RunStats stats, Callable<Step<T>> step) {
        return failingWith(stats, Mono.fromCallable(step));
    }

    private static <T> Mono<T> failingWith(RunStats stats, Mono<T> action) {
        return action.onErrorMap(error -> !(error instanceof StepFailure), error -> new StepFailure(stats, error));
    }

    private static TopicsRunException invariant(String message) {
        return new TopicsRunException(RunError.INVARIANT, message);
    }
}

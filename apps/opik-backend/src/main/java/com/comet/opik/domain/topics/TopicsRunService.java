package com.comet.opik.domain.topics;

import com.comet.opik.domain.IdGenerator;
import com.comet.opik.domain.topics.TopicProject.RunError;
import com.comet.opik.infrastructure.TopicsConfig;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.time.Duration;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;

/**
 * One project's discovery run, as one reactive flow: read the window ({@link TopicWindowReader}), cluster it, make the
 * author call ({@link TopicsLlm}), finish the list into its rows ({@link TopicRunRows}) and save ({@link TopicRunStore}).
 * It owns the run's time limit and its cancel; the scheduler its CPU steps and timers run on is the caller's.
 *
 * <p>The save commits one MySQL transaction first: the list on the project's {@code topic_projects} row, the new topics
 * with their prototype vectors, and the run as {@code saved}. The run's time limit ends at that commit. Then it inserts
 * {@code trace_topics} for the traces it read, with its own bound. A failure before the commit writes nothing but the
 * failed attempt. The topics' sizes and the run's Other share are those of the traces it read: on a sampled window, of
 * the sample. The run's stats go to its log lines, not to a table.
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
     * caller counted as {@code windowTraces} traces. The CPU steps and the timeouts run on {@code compute}, the
     * caller's scheduler. The save leaves the labels incomplete. Never errors: a failure is recorded on the run and
     * returned. A run that outlasts {@code topics.run.projectTimeout} before its commit fails with {@code timeout};
     * one cancelled by its subscriber is failed with {@code timeout} too.
     */
    Mono<TopicRunOutcome> discover(@NonNull UUID runId, @NonNull TopicsMetrics.Workspace workspace,
            @NonNull UUID projectId, @NonNull LocalDate night, long windowTraces, @NonNull Scheduler compute) {
        var run = TopicRun.of(runId, workspace.id(), workspace.name(), projectId, night,
                config.getRun().getWindowDays());
        return Mono.usingWhen(Mono.just(run), started -> run(started, windowTraces, compute),
                started -> Mono.empty(), (started, error) -> Mono.empty(), outcomes::cancelled);
    }

    private Mono<TopicRunOutcome> run(TopicRun run, long windowTraces, Scheduler compute) {
        int maxTopics = config.getAlgorithm().getMaxTopics();
        var timeout = config.getRun().getProjectTimeout().toJavaDuration();
        // What the run has read so far: a failed run's outcome and log carry it. Each step adds to a copy.
        var read = new AtomicReference<>(TopicRunOutcomes.bounds(run));
        return timed(run, "window", reader.read(run, windowTraces, compute))
                .doOnNext(window -> add(read, window.stats()))
                .flatMap(window -> compute(run, "cluster", compute,
                        () -> algorithm.cluster(window.topicWindow(), maxTopics))
                        .doOnNext(clustering -> add(read, clustering.stats()))
                        .flatMap(clustering -> timed(run, "llm",
                                llm.generate(run, clustering.author(), AuthorAnswer::parse))
                                .doOnNext(answer -> add(read, answer.stats()))
                                .flatMap(answer -> compute(run, "assign", compute,
                                        () -> rows(run, window, clustering, answer, read.get())))))
                .flatMap(rows -> timed(run, "save", store.save(run, rows.topics(), rows.otherVectors()))
                        .map(kept -> kept ? rows : rows.discarded()))
                .timeout(timeout, Mono.error(this::timedOut), compute)
                .flatMap(rows -> saved(run, rows, compute))
                .onErrorResume(error -> outcomes.failed(run, error, read.get()));
    }

    /** The list from the answer, as the rows the save writes, with the run's stats; a broken list is an invariant. */
    private TopicRunRows rows(TopicRun run, RunWindow window, Clustering clustering,
            TopicsLlm.Answer<AuthorAnswer> answer, ObjectNode read) {
        TopicRunRows rows;
        try {
            rows = TopicRunRows.of(run, window.traces(), clustering.finish(answer.value()), idGenerator::generateId);
        } catch (IllegalArgumentException exception) {
            throw new TopicsRunException(RunError.INVARIANT, exception.getMessage(), exception);
        }
        var stats = read.deepCopy();
        stats.setAll(rows.stats());
        log.info("Topics run '{}' built, project '{}', window ['{}', '{}'), vectors '{}', topic sizes '{}', Other "
                + "share '{}'", run.runId(), run.projectId(), run.from(), run.to(), window.texts().length,
                rows.topics().stream().map(Topic::size).toList(), TopicText.fixed(rows.otherShare(), 3));
        return rows.toBuilder().stats(stats).build();
    }

    private Mono<TopicRunOutcome> saved(TopicRun run, TopicRunRows rows, Scheduler compute) {
        metrics.otherShare(run.workspace(), rows.otherShare());
        var insert = rows.traceTopics().isEmpty()
                ? Mono.just(false)
                : store.insertTraceTopics(run, rows.traceTopics(), compute);
        return insert.map(inserted -> outcomes.saved(run, rows, inserted));
    }

    private TopicsRunException timedOut() {
        return new TopicsRunException(RunError.TIMEOUT, "the run outlasted topics.run.projectTimeout '%s'"
                .formatted(config.getRun().getProjectTimeout()));
    }

    /** A CPU step on {@code compute}, timed from when it starts there. */
    private <T> Mono<T> compute(TopicRun run, String phase, Scheduler compute, Callable<T> step) {
        return timed(run, phase, Mono.fromCallable(step)).subscribeOn(compute);
    }

    /** Records the phase's duration, monotonic, from its subscription to its end, whatever the end. */
    private <T> Mono<T> timed(TopicRun run, String phase, Mono<T> step) {
        return Mono.defer(() -> {
            long started = System.nanoTime();
            return step.doFinally(
                    signal -> metrics.phase(run.workspace(), phase, Duration.ofNanos(System.nanoTime() - started)));
        });
    }

    private static void add(AtomicReference<ObjectNode> read, ObjectNode stats) {
        read.updateAndGet(previous -> {
            var next = previous.deepCopy();
            next.setAll(stats);
            return next;
        });
    }
}

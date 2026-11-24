package com.comet.opik.domain.topics;

import com.comet.opik.domain.IdGenerator;
import com.comet.opik.domain.topics.TopicProject.RunError;
import com.comet.opik.infrastructure.TopicsConfig;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.time.Duration;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One project's discovery run, as one reactive flow: read the window ({@link TopicWindowReader}), cluster it, make the
 * author call ({@link TopicsLlm}), finish the list into its rows ({@link TopicRunRows}) and save ({@link TopicRunStore}).
 * It owns the run's time limit and its cancel. Its CPU steps, clustering and assignment, run on the scheduler the
 * caller put in the Reactor context ({@link TopicsCpu}).
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
     * caller counted as {@code windowTraces} traces. The save leaves the labels incomplete. Never errors: a failure is recorded on the run and
     * returned. A run that outlasts {@code topics.run.projectTimeout} before its commit fails with {@code timeout};
     * one cancelled by its subscriber is failed with {@code timeout} too.
     */
    Mono<TopicRunOutcome> discover(@NonNull UUID runId, @NonNull TopicsMetrics.Workspace workspace,
            @NonNull UUID projectId, @NonNull LocalDate night, long windowTraces) {
        var run = TopicRun.of(runId, workspace.id(), workspace.name(), projectId, night,
                config.getRun().getWindowDays());
        return Mono.usingWhen(Mono.just(run), started -> run(started, windowTraces),
                started -> Mono.empty(), (started, error) -> Mono.empty(), outcomes::cancelled);
    }

    private Mono<TopicRunOutcome> run(TopicRun run, long windowTraces) {
        int maxTopics = config.getAlgorithm().getMaxTopics();
        var timeout = config.getRun().getProjectTimeout().toJavaDuration();
        // A step's failure carries the stats of the steps before it; a timeout cuts the chain from outside, so it
        // carries the bounds, and the phase metric shows how far the run got.
        return timed(run, "window", reader.read(run, windowTraces))
                .flatMap(window -> timed(run, "cluster",
                        TopicsCpu.step(() -> algorithm.cluster(window.topicWindow(), maxTopics)))
                        .flatMap(clustering -> timed(run, "llm",
                                llm.generate(run, clustering.author(), AuthorAnswer::parse))
                                .flatMap(answer -> timed(run, "assign",
                                        TopicsCpu.step(() -> assign(run, window, clustering, answer))))
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

    /** Records the phase's duration, monotonic, from its subscription to its end, whatever the end. */
    private <T> Mono<T> timed(TopicRun run, String phase, Mono<T> step) {
        return Mono.defer(() -> {
            long started = System.nanoTime();
            return step.doFinally(
                    signal -> metrics.phase(run.workspace(), phase, Duration.ofNanos(System.nanoTime() - started)));
        });
    }

}

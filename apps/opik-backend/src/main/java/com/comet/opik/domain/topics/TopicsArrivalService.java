package com.comet.opik.domain.topics;

import com.comet.opik.infrastructure.TopicsConfig;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.Builder;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Topic on arrival: right after the vectors tick writes a chunk of vectors, each trace gets the topic of its project's
 * saved list, as a {@code trace_topics} row with source {@code arrival} and no run id.
 *
 * <p>The rule is the algorithm's {@link TopicAlgorithm#labeller}, the same one the labelling pass uses, made from the
 * project's topics and the list's known-Other vectors. A workspace's turn reads the saved lists of its read's projects
 * once, before their embed calls ({@link TopicRunStore#readSavedLists}). A project with no saved list, or a list with
 * no topic, has no labeller: its traces get no row and wait for the project's next run. The labelling runs on
 * {@link Schedulers#parallel()}. Only a workspace listed at the arrival stage or a later one gets rows: the
 * vectors tick gives the others {@link ArrivalSteps#OFF}.
 *
 * <p>Every vector it gets has a non-blank text and {@link TopicEmbedding#DIMENSIONS} values, because the tick wrote
 * it, and its trace was live after the write. So nothing is filtered before the labeller.
 *
 * <p>A run's row wins over an arrival row: readers take the latest row per trace by {@code last_updated_at}, and on an
 * equal time the row with {@code source = 'run'}.
 *
 * <p>Best effort: a failure is logged and counted, never returned as an error, and the tick writes and counts its
 * vectors as without arrival; the next run labels those traces. Any failure (the read, the labelling or the insert)
 * stops arrival for the workspace for the rest of the tick: its later chunks count as
 * {@link ArrivalOutcome#AFTER_FAILURE}.
 *
 * <p>Bounded in time: the steps of one workspace in one tick take {@code topics.arrival.timeout} in all, and each one
 * ends before the tick's deadline. A step with less than {@code topics.arrival.minStepTime} left does not start; its
 * traces count as {@link ArrivalOutcome#OVER_BUDGET}. A read or insert that outlasts the time left fails with error
 * type {@code TimeoutException}.
 *
 * <p>Holds no state: each step takes the workspace's {@link ArrivalSteps} and returns the next.
 */
@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class TopicsArrivalService {

    private final @NonNull @Config("topics") TopicsConfig config;
    private final @NonNull TopicRunStore store;
    private final @NonNull TopicAlgorithm algorithm;
    private final @NonNull TopicsArrivalMetrics metrics;

    /**
     * Reads the saved lists of those of {@code projectIds} not read yet this tick, in one read-only transaction, and
     * asks the algorithm for each list's labeller.
     *
     * @return {@code steps} with those projects read and their labellers; unchanged when arrival is off or stopped,
     *         every project is read already, or less than {@code topics.arrival.minStepTime} is left; stopped when the
     *         read fails
     */
    Mono<ArrivalSteps> loadLabellers(@NonNull TopicVectorsMetrics.Workspace workspace,
            @NonNull Collection<UUID> projectIds,
            @NonNull TickBudget budget, @NonNull ArrivalSteps steps) {
        var toRead = projectIds.stream().filter(projectId -> !steps.read().contains(projectId)).distinct().toList();
        if (!steps.on() || steps.stopped() || toRead.isEmpty()) {
            return Mono.just(steps);
        }
        var left = left(steps, budget);
        if (left.compareTo(config.getArrival().getMinStepTime().toJavaDuration()) < 0) {
            // Not read: the chunks of these projects count as over_budget.
            return Mono.just(steps);
        }
        return timed(workspace, steps, store.readSavedLists(workspace.id(), toRead)
                .timeout(left)
                .map(saved -> {
                    var read = new HashSet<>(steps.read());
                    read.addAll(toRead);
                    var labellers = new HashMap<>(steps.labellers());
                    // A list with no topic would label every trace Other: it gets no row, as no list.
                    saved.stream().filter(list -> !list.topics().isEmpty())
                            .forEach(list -> labellers.put(list.project().projectId(),
                                    algorithm.labeller(list.topics(), list.project().otherVectors())));
                    return steps.toBuilder().read(Set.copyOf(read)).labellers(Map.copyOf(labellers)).build();
                })
                .onErrorResume(exception -> {
                    log.warn("Topic arrival read failed, arrival stops for the workspace this tick, workspace '{}', "
                            + "projects '{}'", workspace.id(), toRead.size(), exception);
                    return Mono.just(stop(workspace, steps, ArrivalPhase.READ, exception));
                }));
    }

    /**
     * Labels the vectors one write put in, all of one workspace, and writes their {@code trace_topics} rows, within
     * what is left of the workspace's {@code topics.arrival.timeout} and of the tick's budget. Never fails.
     */
    Mono<ArrivalSteps> label(@NonNull TopicVectorsMetrics.Workspace workspace, @NonNull List<TopicVector> vectors,
            @NonNull TickBudget budget, @NonNull ArrivalSteps steps) {
        if (!steps.on() || vectors.isEmpty()) {
            return Mono.just(steps);
        }
        if (steps.stopped()) {
            metrics.labelled(workspace, ArrivalOutcome.AFTER_FAILURE, vectors.size());
            return Mono.just(steps);
        }
        var left = left(steps, budget);
        var unread = vectors.stream().filter(vector -> !steps.read().contains(vector.projectId())).toList();
        metrics.labelled(workspace, ArrivalOutcome.OVER_BUDGET, unread.size());
        var read = vectors.stream().filter(vector -> steps.read().contains(vector.projectId())).toList();
        if (read.isEmpty()) {
            return Mono.just(steps);
        }
        if (left.compareTo(config.getArrival().getMinStepTime().toJavaDuration()) < 0) {
            metrics.labelled(workspace, ArrivalOutcome.OVER_BUDGET, read.size());
            return Mono.just(steps);
        }
        var clock = budget.clock();
        var until = clock.instant().plus(left);
        return timed(workspace, steps, Mono.fromCallable(() -> rows(steps.labellers(), read))
                .flatMap(labelled -> {
                    metrics.labelled(workspace, ArrivalOutcome.NO_PROTOTYPES, labelled.noPrototypes());
                    var rest = Duration.between(clock.instant(), until);
                    if (labelled.rows().isEmpty()) {
                        return Mono.just(steps);
                    }
                    if (!rest.isPositive()) {
                        metrics.labelled(workspace, ArrivalOutcome.OVER_BUDGET, labelled.rows().size());
                        return Mono.just(steps);
                    }
                    return insert(workspace, labelled, rest, steps);
                })
                .onErrorResume(exception -> {
                    log.warn("Topic arrival failed, arrival stops for the workspace this tick, workspace '{}', "
                            + "vectors '{}'", workspace.id(), read.size(), exception);
                    return Mono.just(stop(workspace, steps, ArrivalPhase.ASSIGN, exception));
                }))
                // Outside the timer: the step's duration leaves out the wait for a parallel thread.
                .subscribeOn(Schedulers.parallel());
    }

    private Mono<ArrivalSteps> insert(TopicVectorsMetrics.Workspace workspace, Labelled labelled, Duration left,
            ArrivalSteps steps) {
        var rows = labelled.rows();
        return store.insertTraceTopics(new TopicsMetrics.Workspace(workspace.id(), workspace.name()), rows)
                .timeout(left)
                .then(Mono.fromCallable(() -> {
                    metrics.labelled(workspace, ArrivalOutcome.TOPIC, labelled.topics());
                    metrics.labelled(workspace, ArrivalOutcome.OTHER, labelled.other());
                    return steps;
                }))
                .onErrorResume(exception -> {
                    log.warn("Topic arrival insert failed, arrival stops for the workspace this tick, workspace '{}', "
                            + "rows '{}'", workspace.id(), rows.size(), exception);
                    return Mono.just(stop(workspace, steps, ArrivalPhase.INSERT, exception));
                });
    }

    /** The step, with the time it took recorded and added to the workspace's time spent. */
    private Mono<ArrivalSteps> timed(TopicVectorsMetrics.Workspace workspace, ArrivalSteps before,
            Mono<ArrivalSteps> step) {
        return Mono.defer(() -> {
            long started = System.nanoTime();
            return step.map(after -> {
                var took = Duration.ofNanos(System.nanoTime() - started);
                metrics.duration(workspace, took);
                return after.spend(took);
            });
        });
    }

    private ArrivalSteps stop(TopicVectorsMetrics.Workspace workspace, ArrivalSteps steps, ArrivalPhase phase,
            Throwable error) {
        metrics.failure(workspace, phase, error);
        return steps.toBuilder().stopped(true).build();
    }

    /** What is left of the workspace's arrival time and of the tick's budget. */
    private Duration left(ArrivalSteps steps, TickBudget budget) {
        var ownLeft = config.getArrival().getTimeout().toJavaDuration().minus(steps.spent());
        var left = ownLeft.compareTo(budget.left()) <= 0 ? ownLeft : budget.left();
        return left.isNegative() ? Duration.ZERO : left;
    }

    /** The rows of the vectors whose project has a labeller; no I/O. */
    private static Labelled rows(Map<UUID, TopicLabeller> labellers, List<TopicVector> vectors) {
        var byProject = vectors.stream().collect(Collectors.groupingBy(TopicVector::projectId, LinkedHashMap::new,
                Collectors.toList()));
        var rows = new ArrayList<TraceTopic>();
        int topics = 0;
        int other = 0;
        int noPrototypes = 0;
        for (var entry : byProject.entrySet()) {
            var group = entry.getValue();
            var labeller = labellers.get(entry.getKey());
            if (labeller == null) {
                noPrototypes += group.size();
                continue;
            }
            var topicIds = labeller.label(group.stream().map(TopicVector::vector).toList());
            for (int i = 0; i < group.size(); i++) {
                var vector = group.get(i);
                rows.add(TraceTopic.builder()
                        .workspaceId(vector.workspaceId())
                        .projectId(vector.projectId())
                        .traceId(vector.traceId())
                        .traceCreatedAt(vector.traceCreatedAt())
                        .topicId(topicIds[i])
                        .source(TraceTopic.Source.ARRIVAL)
                        .runId(null)
                        .build());
                if (TraceTopic.OTHER.equals(topicIds[i])) {
                    other++;
                } else {
                    topics++;
                }
            }
        }
        return Labelled.builder().rows(rows).topics(topics).other(other).noPrototypes(noPrototypes).build();
    }

    /** One chunk's rows, with the count of traces per outcome: topic, other, and no_prototypes (no row). */
    @Builder(toBuilder = true)
    private record Labelled(List<TraceTopic> rows, int topics, int other, int noPrototypes) {
    }
}

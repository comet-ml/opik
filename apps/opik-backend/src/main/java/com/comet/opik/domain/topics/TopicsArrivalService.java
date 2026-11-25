package com.comet.opik.domain.topics;

import com.comet.opik.infrastructure.TopicsConfig;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;

/**
 * Topic on arrival: right after the vectors tick inserts a chunk of vectors, each trace gets the topic of its project's
 * saved list, as a {@code trace_topics} row with source {@code arrival} and no run id.
 *
 * <p>The rule is the algorithm's {@link TopicAlgorithm#labeller}, the same one the labelling pass uses, made from the
 * project's {@code topics} rows and the list's known-Other vectors ({@code topic_projects.other_vectors}). A tick reads
 * each project's list once, in one read-only MySQL transaction for the projects of a chunk. A project with no saved
 * list, or a list with no topic, has no labeller: its traces get no row and wait for the project's next run. The
 * labelling runs on {@link Schedulers#parallel()}.
 *
 * <p>Every vector it gets has a non-blank text and {@link TopicEmbedding#DIMENSIONS} values, because the tick wrote
 * it: the tick embeds only a non-blank summary and keeps only vectors of that length, the length of a saved prototype.
 * So nothing is filtered before the labeller.
 *
 * <p>A run's row wins over an arrival row: readers take the latest row per trace by {@code last_updated_at}, and on an
 * equal time the row with {@code source = 'run'}.
 *
 * <p>Best effort: a failure is logged and counted, never returned as an error, and the tick writes and counts its
 * vectors as without arrival; the next run labels those traces. Any failure (the read, the insert, or anything else in
 * a step) stops arrival for the rest of the tick, so a failing MySQL or ClickHouse costs the tick at most one step's
 * time per workspace in flight. The traces of the later chunks count as {@link ArrivalOutcome#AFTER_FAILURE}.
 *
 * <p>Bounded in time: the steps of one workspace in one tick take {@code topics.arrival.timeout} in all, and each one
 * ends before the tick's deadline. A workspace's steps run one after another, so its time spent is exact while the
 * tick visits several workspaces at once. A step with less than {@code topics.arrival.minStepTime} left does not
 * start; its traces count as {@link ArrivalOutcome#OVER_BUDGET}. A read or insert that outlasts the time left fails
 * with error type {@code TimeoutException}.
 */
@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class TopicsArrivalService {

    private final @NonNull @Config("topics") TopicsConfig config;
    private final @NonNull TransactionTemplate template;
    private final @NonNull TraceTopicsDAO traceTopicsDAO;
    private final @NonNull TopicAlgorithm algorithm;
    private final @NonNull TopicsArrivalMetrics metrics;

    /** One tick's arrival steps; a no-op when arrival is off. */
    Tick tick() {
        return new Tick(config.getArrival().isEnabled());
    }

    /** The arrival steps of one tick. Safe to share between the workspaces the tick visits at once. */
    final class Tick {

        private final boolean enabled;
        // Per project read this tick: its labeller, or empty when it has no saved list or one with no topic.
        private final Map<UUID, Optional<TopicLabeller>> labellers = new ConcurrentHashMap<>();
        // Set by any failure: arrival does nothing more this tick.
        private final AtomicBoolean stopped = new AtomicBoolean();
        // Per workspace: the time its steps took this tick.
        private final Map<String, AtomicLong> spentNanosByWorkspace = new ConcurrentHashMap<>();

        private Tick(boolean enabled) {
            this.enabled = enabled;
        }

        /**
         * Labels the vectors one insert wrote, all of one workspace, and writes their {@code trace_topics} rows, within
         * what is left of the workspace's {@code topics.arrival.timeout} for the tick and of the tick's budget.
         * Completes empty, and never with an error.
         */
        Mono<Void> label(@NonNull TopicVectorsMetrics.Workspace workspace, @NonNull List<TopicVector> vectors,
                @NonNull TickBudget budget) {
            if (!enabled || vectors.isEmpty()) {
                return Mono.empty();
            }
            return Mono.defer(() -> {
                if (stopped.get()) {
                    metrics.labelled(workspace, ArrivalOutcome.AFTER_FAILURE, vectors.size());
                    return Mono.<Void>empty();
                }
                var arrival = config.getArrival();
                var spent = spentNanosByWorkspace.computeIfAbsent(workspace.id(), __ -> new AtomicLong());
                var left = min(arrival.getTimeout().toJavaDuration().minusNanos(spent.get()), budget.left());
                if (left.compareTo(arrival.getMinStepTime().toJavaDuration()) < 0) {
                    metrics.labelled(workspace, ArrivalOutcome.OVER_BUDGET, vectors.size());
                    return Mono.<Void>empty();
                }
                var clock = budget.clock();
                var until = clock.instant().plus(left);
                long started = System.nanoTime();
                return Mono.defer(() -> labelChunk(workspace, vectors, until, clock))
                        .onErrorResume(exception -> {
                            stop(workspace, ArrivalPhase.ASSIGN, exception);
                            log.warn("Topic arrival failed, arrival stops for this tick, workspace '{}', vectors '{}'",
                                    workspace.id(), vectors.size(), exception);
                            return Mono.empty();
                        })
                        .doFinally(signal -> {
                            long took = System.nanoTime() - started;
                            spent.addAndGet(took);
                            metrics.duration(workspace, Duration.ofNanos(took));
                        });
            });
        }

        private Mono<Void> labelChunk(TopicVectorsMetrics.Workspace workspace, List<TopicVector> vectors,
                Instant until, Clock clock) {
            var byProject = vectors.stream().collect(Collectors.groupingBy(TopicVector::projectId,
                    LinkedHashMap::new, Collectors.toList()));
            return read(workspace, byProject.keySet(), until, clock)
                    .flatMap(__ -> Mono.fromCallable(() -> rows(byProject)).subscribeOn(Schedulers.parallel()))
                    .flatMap(labelled -> {
                        metrics.labelled(workspace, ArrivalOutcome.NO_PROTOTYPES, labelled.noPrototypes());
                        return insert(workspace, labelled, until, clock);
                    });
        }

        /** The rows of the projects that have a labeller; no I/O. */
        private Labelled rows(Map<UUID, List<TopicVector>> byProject) {
            var rows = new ArrayList<TraceTopic>();
            int topics = 0;
            int other = 0;
            int noPrototypes = 0;
            for (var entry : byProject.entrySet()) {
                var group = entry.getValue();
                var labeller = labellers.get(entry.getKey());
                if (labeller.isEmpty()) {
                    noPrototypes += group.size();
                    continue;
                }
                var topicIds = labeller.get().label(group.stream().map(TopicVector::vector).toList());
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
            return new Labelled(rows, topics, other, noPrototypes);
        }

        /**
         * Reads the saved lists of the projects this tick has not read yet, in one read-only transaction, and asks the
         * algorithm for each project's labeller. Stops waiting at {@code until}. Emits once the labellers are known;
         * a failed read stops arrival for the tick and completes empty.
         */
        private Mono<Boolean> read(TopicVectorsMetrics.Workspace workspace, Set<UUID> projectIds, Instant until,
                Clock clock) {
            var toRead = projectIds.stream().filter(projectId -> !labellers.containsKey(projectId)).toList();
            if (toRead.isEmpty()) {
                return Mono.just(true);
            }
            var workspaceId = workspace.id();
            return Mono.fromCallable(() -> template.inTransaction(READ_ONLY, handle -> {
                return new SavedLists(handle.attach(TopicProjectDAO.class).findProjects(workspaceId, toRead),
                        handle.attach(TopicDAO.class).findByProjects(workspaceId, toRead));
            }))
                    .subscribeOn(Schedulers.boundedElastic())
                    .timeout(left(until, clock))
                    .onErrorResume(exception -> {
                        stop(workspace, ArrivalPhase.READ, exception);
                        log.warn("Topic arrival read failed, arrival stops for this tick, workspace '{}', projects "
                                + "'{}'", workspaceId, toRead.size(), exception);
                        return Mono.empty();
                    })
                    .map(saved -> {
                        var projects = saved.projects().stream()
                                .collect(Collectors.toMap(TopicProject::projectId, Function.identity()));
                        // In (born, id) order within each project: the query's order, which grouping keeps.
                        var topics = saved.topics().stream().collect(Collectors.groupingBy(Topic::projectId));
                        for (var projectId : toRead) {
                            var project = projects.get(projectId);
                            var live = topics.getOrDefault(projectId, List.<Topic>of());
                            // A list with no topic would label every trace Other: it gets no row, as no list.
                            labellers.put(projectId, project == null || project.listRunId() == null || live.isEmpty()
                                    ? Optional.empty()
                                    : Optional.of(algorithm.labeller(live, project.otherVectors())));
                        }
                        return true;
                    });
        }

        /**
         * The rows are built: they are inserted with whatever time is left, and count as over_budget only when none
         * is.
         */
        private Mono<Void> insert(TopicVectorsMetrics.Workspace workspace, Labelled labelled, Instant until,
                Clock clock) {
            var rows = labelled.rows();
            if (rows.isEmpty()) {
                return Mono.empty();
            }
            var left = left(until, clock);
            if (!left.isPositive()) {
                metrics.labelled(workspace, ArrivalOutcome.OVER_BUDGET, rows.size());
                return Mono.empty();
            }
            return Mono.defer(() -> traceTopicsDAO.insert(rows))
                    .timeout(left)
                    .doOnSuccess(inserted -> {
                        metrics.labelled(workspace, ArrivalOutcome.TOPIC, labelled.topics());
                        metrics.labelled(workspace, ArrivalOutcome.OTHER, labelled.other());
                    })
                    .onErrorResume(exception -> {
                        stop(workspace, ArrivalPhase.INSERT, exception);
                        log.warn("Topic arrival insert failed, arrival stops for this tick, workspace '{}', rows '{}'",
                                workspace.id(), rows.size(), exception);
                        return Mono.empty();
                    })
                    .then();
        }

        private void stop(TopicVectorsMetrics.Workspace workspace, ArrivalPhase phase, Throwable error) {
            stopped.set(true);
            metrics.failure(workspace, phase, error);
        }
    }

    private record SavedLists(List<TopicProject> projects, List<Topic> topics) {
    }

    /** One chunk's rows, with the count of traces per outcome: topic, other, and no_prototypes (no row). */
    private record Labelled(List<TraceTopic> rows, int topics, int other, int noPrototypes) {
    }

    private static Duration min(Duration a, Duration b) {
        return a.compareTo(b) <= 0 ? a : b;
    }

    private static Duration left(Instant until, Clock clock) {
        var left = Duration.between(clock.instant(), until);
        return left.isNegative() ? Duration.ZERO : left;
    }
}

package com.comet.opik.domain.topics;

import com.comet.opik.domain.topics.TopicProjectDAO.Backfill;
import com.comet.opik.domain.topics.TopicProjectDAO.Progress;
import com.comet.opik.domain.topics.TopicVectorsDAO.Candidate;
import com.comet.opik.infrastructure.TopicsConfig;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * The topic vectors backfill: the traces of projects whose done statuses go back further than the short window, read
 * one project at a time over the backfill window, so the read follows the tables' sort keys. A project's row in
 * {@code topic_projects} says whether it still has to be read; ClickHouse alone says which traces have no vector.
 *
 * <p>About once per {@code longWindowInterval} per workspace, the tick discovers the projects with done statuses in the
 * backfill window and creates or refreshes their rows. A project the workspace read sees for the first time gets a row
 * too, so its traces are this class's from then on.
 */
@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class TopicVectorBackfiller {

    /**
     * The most undrained projects a workspace read leaves out by id: the ids go into the query text, about 40 bytes
     * each. A workspace with more is not read by the workspace read until its backfill brings it under.
     */
    static final int MAX_EXCLUDED_PROJECTS = 2_000;
    /** The most workspaces whose projects one tick discovers, so a new leader's first ticks still serve traces. */
    static final int MAX_DISCOVERIES_PER_TICK = 5;
    /**
     * A project with at most this many traces in the backfill window, as discovered, is read in one query over the
     * whole window; a larger one in {@code find.sliceSize} slices. Measured on ClickHouse 26.3 with the real DDL: one
     * read of a project costs about 0.45 KB of memory and 6 rows read per trace, so at this value one read stays near
     * 110 MB and 1.5M rows, an eighth of the default {@code find.maxMemoryUsage}, which leaves room for the count being
     * approximate.
     */
    static final long ONE_SLICE_MAX_TRACES = 250_000;

    private final @NonNull @Config("topics") TopicsConfig config;
    private final @NonNull TopicVectorsDAO dao;
    private final @NonNull TopicVectorBackfillService backfills;
    private final @NonNull TopicVectorWriter writer;
    private final @NonNull TopicVectorsMetrics metrics;

    /**
     * When this pod last discovered each workspace's projects. Only a hint for when to discover again: a new leader
     * starts empty and discovers every workspace once more, which changes no row's drained mark.
     */
    private final Map<String, Instant> discoveredAt = new ConcurrentHashMap<>();
    /** The workspaces whose workspace reads this pod is skipping, each logged once until it clears. */
    private final Set<String> overExcluded = ConcurrentHashMap.newKeySet();

    /** A workspace read's candidates to serve, and the projects it saw for the first time and started. */
    record Served(@NonNull List<Candidate> candidates, @NonNull List<Backfill> started) {
    }

    /** One project's turn: what to record, if anything, and whether the project should get another turn. */
    private record Turn(@NonNull TickResult result, @NonNull Optional<Progress> progress, boolean more) {
    }

    /**
     * Backfill first those that can drain in one read, smallest first, then the large ones; the most recently active
     * first among equals, so the projects users look at get their vectors first.
     */
    static Comparator<Backfill> order(int maxTracesPerTick) {
        Comparator<Backfill> recentFirst = Comparator.comparing(Backfill::backfillLastDoneAt).reversed();
        return Comparator
                .comparing((Backfill backfill) -> backfill.remaining() > maxTracesPerTick)
                .thenComparingLong(backfill -> backfill.remaining() > maxTracesPerTick ? 0 : backfill.remaining())
                .thenComparing(recentFirst)
                .thenComparing(Backfill::projectId);
    }

    /** The started backfills not drained yet, by workspace. */
    Mono<Map<String, List<Backfill>>> undrained(@NonNull List<String> workspaceIds) {
        return Mono.fromCallable(() -> backfills.findUndrained(workspaceIds)).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Discovers the projects of the workspaces due for it, one at a time: never discovered by this pod, or not for
     * {@code longWindowInterval}. Per workspace, two plain aggregates over the backfill window: the projects with done
     * statuses, and their vector counts. A workspace's rows not checked for twice the interval start again, because the
     * tick did not run for it meanwhile. A workspace no longer enabled is forgotten, so one enabled again is discovered
     * at its first tick.
     *
     * @param lowerBound the backfill window's start
     * @param createdAfter the backfill window's {@code trace_created_at} bound
     */
    Mono<Void> discover(@NonNull List<String> workspaceIds, @NonNull Instant lowerBound,
            @NonNull Instant createdAfter, @NonNull TickBudget budget) {
        return Mono.defer(() -> {
            discoveredAt.keySet().retainAll(new HashSet<>(workspaceIds));
            var interval = config.getVectors().getLongWindowInterval().toJavaDuration();
            var due = workspaceIds.stream()
                    .filter(id -> Optional.ofNullable(discoveredAt.get(id))
                            .map(last -> Duration.between(last, budget.start()).compareTo(interval) >= 0)
                            .orElse(true))
                    .limit(MAX_DISCOVERIES_PER_TICK)
                    .toList();
            long started = System.nanoTime();
            return Flux.fromIterable(due)
                    .concatMap(workspaceId -> discover(workspaceId, lowerBound, createdAfter, budget, interval))
                    .then(Mono.fromRunnable(() -> metrics.phase("discover",
                            Duration.ofNanos(System.nanoTime() - started))));
        });
    }

    private Mono<Void> discover(String workspaceId, Instant lowerBound, Instant createdAfter, TickBudget budget,
            Duration interval) {
        return Mono.defer(() -> {
            var reserve = config.getVectors().getEmbedTimeout().toJavaDuration();
            if (!budget.hasTimeForRead(reserve)) {
                return Mono.empty();
            }
            var maxExecutionTime = config.getVectors().getFind().getMaxExecutionTime().toJavaDuration();
            var start = budget.start();
            return dao.findBackfillProjects(workspaceId, TopicEmbedding.SUMMARY_TASK, lowerBound, createdAfter,
                    budget.readSeconds(maxExecutionTime, reserve))
                    .collectList()
                    .flatMap(projects -> dao.countTracesWithVectorsByProject(workspaceId, TopicEmbedding.SUMMARY_TASK,
                            createdAfter, projects.stream().map(TopicVectorsDAO.BackfillProject::projectId).toList(),
                            budget.readSeconds(maxExecutionTime, reserve))
                            .map(withVectors -> projects.stream()
                                    .map(project -> Backfill.builder()
                                            .workspaceId(workspaceId)
                                            .projectId(project.projectId())
                                            .backfillStartedAt(start)
                                            .backfillLastDoneAt(project.lastDoneAt())
                                            .backfillTracesTotal(project.traces())
                                            .backfillTracesDone(Math.min(project.traces(),
                                                    withVectors.getOrDefault(project.projectId(), 0L)))
                                            .build())
                                    .toList()))
                    .flatMap(rows -> Mono.fromCallable(
                            () -> backfills.discovered(workspaceId, rows, start, start.minus(interval.multipliedBy(2))))
                            .subscribeOn(Schedulers.boundedElastic()))
                    .doOnNext(removed -> {
                        if (removed > 0) {
                            log.info("Topic vectors backfill rows of deleted projects removed, workspace '{}', rows "
                                    + "'{}'", workspaceId, removed);
                        }
                        discoveredAt.put(workspaceId, start);
                    })
                    .onErrorResume(exception -> {
                        log.warn("Topic vectors project discovery failed, trying again next tick, workspace '{}'",
                                workspaceId, exception);
                        return metrics.workspace(workspaceId)
                                .doOnNext(workspace -> metrics.readFailure("discover", workspace, exception))
                                .then(Mono.empty());
                    })
                    .then();
        });
    }

    /**
     * The projects a workspace read leaves out, those in backfill; empty when the workspace must not be read. That is
     * when there are more than {@link #MAX_EXCLUDED_PROJECTS}: the workspace read skips it until its backfill brings it
     * under, and its traces are the backfill's for now.
     */
    Optional<List<UUID>> readableWithout(@NonNull String workspaceId, @NonNull List<Backfill> undrained) {
        if (undrained.size() > MAX_EXCLUDED_PROJECTS) {
            if (overExcluded.add(workspaceId)) {
                log.warn("Topic vectors workspace reads skipped until the backfill brings the projects in backfill "
                        + "under the limit, workspace '{}', projects in backfill '{}', limit '{}'", workspaceId,
                        undrained.size(), MAX_EXCLUDED_PROJECTS);
            }
            return Optional.empty();
        }
        if (overExcluded.remove(workspaceId)) {
            log.info("Topic vectors workspace reads resumed, workspace '{}', projects in backfill '{}'", workspaceId,
                    undrained.size());
        }
        return Optional.of(undrained.stream().map(Backfill::projectId).toList());
    }

    /**
     * A workspace read's candidates without those of projects that have no drained row. Such a project is new to the
     * tick: it gets a row, not drained, and its traces are left to the backfill, which reads the project's whole
     * window in this tick.
     */
    Mono<Served> withoutNewProjects(@NonNull String workspaceId, @NonNull List<Candidate> candidates,
            @NonNull Instant start) {
        if (candidates.isEmpty()) {
            return Mono.just(new Served(candidates, List.of()));
        }
        return Mono.fromCallable(() -> {
            var lastDone = candidates.stream().collect(Collectors.toMap(Candidate::projectId,
                    Candidate::statusUpdatedAt, (a, b) -> a.isAfter(b) ? a : b, LinkedHashMap::new));
            var known = backfills.findByKeys(workspaceId, lastDone.keySet());
            var started = lastDone.entrySet().stream()
                    .filter(entry -> !known.containsKey(entry.getKey()))
                    .map(entry -> Backfill.builder()
                            .workspaceId(workspaceId)
                            .projectId(entry.getKey())
                            .backfillStartedAt(start)
                            .backfillLastDoneAt(entry.getValue())
                            .build())
                    .toList();
            if (!started.isEmpty()) {
                backfills.start(started);
                log.info("Topic vectors backfill started for new projects, workspace '{}', projects '{}'",
                        workspaceId, started.size());
            }
            var served = candidates.stream()
                    .filter(candidate -> Optional.ofNullable(known.get(candidate.projectId()))
                            .map(Backfill::backfillDrainedAt).isPresent())
                    .toList();
            return new Served(served, started);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * One workspace's backfill: its projects not drained yet, one at a time, in {@link #order}, while the tick has time
     * for an embed call. A project that wrote vectors and still has more than one read's worth takes the next turn
     * too, so one large project uses the time the tick has, not one {@code maxTracesPerTick}. An embed call that fails
     * ends the workspace's backfill for the tick. The projects drained and the vectors written are recorded in one
     * write at the end; a failed write only means the projects are read again next tick.
     *
     * @param window the backfill window's bounds and the workspace
     */
    Mono<TickResult> backfill(@NonNull TopicVectorsMetrics.Workspace workspace, @NonNull Collection<Backfill> projects,
            @NonNull TopicVectorsFind window, @NonNull TickBudget budget) {
        if (projects.isEmpty()) {
            return Mono.just(TickResult.EMPTY);
        }
        var vectors = config.getVectors();
        var embedTimeout = vectors.getEmbedTimeout().toJavaDuration();
        var run = new Run(projects.stream().sorted(order(vectors.getMaxTracesPerTick()))
                .collect(Collectors.toCollection(ArrayDeque::new)));
        // repeat() runs the turns one after another, so only one thread at a time touches the run.
        return Mono.defer(() -> {
            var project = run.queue.poll();
            return turn(workspace, project, window, budget).doOnNext(turn -> run.add(project, turn));
        })
                .repeat(() -> !run.stopped && !run.queue.isEmpty() && budget.hasTimeForRead(embedTimeout))
                .then(Mono.fromCallable(() -> {
                    backfills.record(run.progress);
                    return run.result;
                }).subscribeOn(Schedulers.boundedElastic()))
                .onErrorResume(exception -> {
                    log.warn("Topic vectors backfill progress not recorded, reading them again next tick, workspace "
                            + "'{}', projects '{}'", window.workspaceId(), run.progress.size(), exception);
                    return Mono.just(run.result);
                });
    }

    /** One workspace's backfill in progress: the projects still to take a turn, and what the turns did. */
    private static final class Run {

        private final ArrayDeque<Backfill> queue;
        private final List<Progress> progress = new ArrayList<>();
        private TickResult result = TickResult.EMPTY;
        private boolean stopped;

        private Run(ArrayDeque<Backfill> queue) {
            this.queue = queue;
        }

        private void add(Backfill project, Turn turn) {
            result = result.plus(turn.result());
            turn.progress().ifPresent(progress::add);
            if (turn.more()) {
                queue.addFirst(project);
            }
            stopped = turn.result().skipped().containsKey(SkipReason.EMBED_FAILED);
        }
    }

    /**
     * One project's turn: read its window, embed, write. The project is drained when the read was complete and none of
     * its candidates were left for a later tick ({@link TickResult#cut}). No progress when there is nothing to record:
     * the read failed, or nothing was written and the project is not drained. More: the read stopped at the cap, none
     * was left and vectors were written, so another read now finds the next ones.
     */
    private Mono<Turn> turn(TopicVectorsMetrics.Workspace workspace, Backfill project, TopicVectorsFind window,
            TickBudget budget) {
        var vectors = config.getVectors();
        var workspaceId = project.workspaceId();
        // A project the workspace read just started has no count yet (0): read it in slices too, as it may be large.
        var traces = project.backfillTracesTotal();
        var sliceSize = traces == 0 || traces > ONE_SLICE_MAX_TRACES
                ? vectors.getFind().getSliceSize().toJavaDuration()
                : null;
        return WindowRead.read(dao, window.toBuilder().projectId(project.projectId()).build(), sliceSize, budget,
                vectors.getFind().getMaxExecutionTime().toJavaDuration(), vectors.getEmbedTimeout().toJavaDuration())
                .flatMap(read -> writer.embedAndWrite(workspace, read.candidates(), budget, null)
                        .map(result -> {
                            boolean drained = read.complete() && !result.cut();
                            if (!drained && result.written() == 0) {
                                return new Turn(result, Optional.empty(), false);
                            }
                            return new Turn(result, Optional.of(Progress.builder()
                                    .workspaceId(workspaceId)
                                    .projectId(project.projectId())
                                    .drainedAt(drained ? budget.start() : null)
                                    .written(result.written())
                                    .build()), !read.complete() && !result.cut());
                        }))
                .onErrorResume(exception -> {
                    metrics.readFailure("backfill", workspace, exception);
                    log.warn("Topic vectors backfill read failed, reading it again next tick, workspace '{}', project "
                            + "'{}'", workspaceId, project.projectId(), exception);
                    return Mono.just(new Turn(TickResult.EMPTY, Optional.empty(), false));
                });
    }
}

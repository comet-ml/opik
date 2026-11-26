package com.comet.opik.domain.topics;

import com.comet.opik.domain.topics.TopicVectorsDAO.Candidate;
import com.comet.opik.infrastructure.TopicsConfig;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.Builder;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The topic vectors backfill: the traces of projects whose done statuses go back further than the short window, read
 * one project at a time over the backfill window, so the read follows the tables' sort keys. A project's row in
 * {@code topic_projects} says whether it still has to be read; ClickHouse alone says which traces have no vector.
 *
 * <p>{@link TopicVectorDiscovery} creates and refreshes the rows. A project the workspace read sees for the first time
 * gets a row too, so its traces are this class's from then on.
 */
@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class TopicVectorBackfiller {

    private final @NonNull @Config("topics") TopicsConfig config;
    private final @NonNull TopicVectorsDAO dao;
    private final @NonNull TopicVectorBackfillService backfills;
    private final @NonNull TopicVectorWriter writer;
    private final @NonNull TopicVectorsMetrics metrics;

    /** A workspace read's candidates to serve, and the projects it saw for the first time and started. */
    record Served(@NonNull List<Candidate> candidates, @NonNull List<TopicBackfill> started) {
    }

    /**
     * One project's turn: what to record, if anything, whether the project should get another turn, the keys it
     * settled, and the workspace's topic on arrival after it.
     */
    private record Turn(@NonNull TickResult result, @NonNull Optional<TopicBackfillProgress> progress, boolean more,
            @NonNull Set<VectorKey> settled, @NonNull ArrivalSteps arrival) {
    }

    /**
     * Backfill first those that can drain in one read, smallest first, then the large ones; the most recently active
     * first among equals, so the projects users look at get their vectors first.
     */
    static Comparator<TopicBackfill> order(int maxTracesPerTick) {
        Comparator<TopicBackfill> recentFirst = Comparator.comparing(TopicBackfill::backfillLastDoneAt).reversed();
        return Comparator
                .comparing((TopicBackfill backfill) -> backfill.remaining() > maxTracesPerTick)
                .thenComparingLong(backfill -> backfill.remaining() > maxTracesPerTick ? 0 : backfill.remaining())
                .thenComparing(recentFirst)
                .thenComparing(TopicBackfill::projectId);
    }

    /** The started backfills not drained yet, by workspace. */
    Mono<Map<String, List<TopicBackfill>>> undrained(@NonNull List<String> workspaceIds) {
        return Mono.fromCallable(() -> backfills.findUndrained(workspaceIds)).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * The projects a workspace read leaves out, those in backfill; empty when the workspace must not be read. That is
     * when there are more than {@code topics.vectors.maxExcludedProjects}: the workspace read skips it until its backfill brings it
     * under, and its traces are the backfill's for now.
     */
    Optional<List<UUID>> readableWithout(@NonNull String workspaceId, @NonNull List<TopicBackfill> undrained) {
        if (undrained.size() > config.getVectors().getMaxExcludedProjects()) {
            return Optional.empty();
        }
        return Optional.of(undrained.stream().map(TopicBackfill::projectId).toList());
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
                    .map(entry -> TopicBackfill.builder()
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
                            .map(TopicBackfill::backfillDrainedAt).isPresent())
                    .toList();
            return new Served(served, started);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * One workspace's part of the backfill phase.
     *
     * @param window the backfill window's bounds and the workspace
     * @param arrival the workspace's topic on arrival after its fresh phase
     */
    @Builder(toBuilder = true)
    record WorkspaceBackfill(@NonNull TopicVectorsMetrics.Workspace workspace,
            @NonNull Collection<TopicBackfill> projects, @NonNull TopicVectorsFind window,
            @NonNull ArrivalSteps arrival) {
    }

    /**
     * The backfill phase: each workspace's projects not drained yet, one turn per workspace per round, the workspaces
     * up to {@code embedConcurrency} at a time, while the tick has time for an embed call, so no workspace takes the
     * whole phase. Inside a workspace, its projects take their turns in {@link #order}; a project that wrote vectors
     * and still has more than one read's worth takes the workspace's next turn too, so one large project uses the time
     * the tick has, not one {@code maxTracesPerTick}. An embed call that fails ends the workspace's backfill for the
     * tick. Each workspace's projects drained and vectors written are recorded in one write when its backfill ends:
     * in the round that ends it, or, for one the budget stopped, at the end of the phase, so a tick cancelled mid-phase
     * keeps what the ended workspaces did. A failed write only means the projects are read again next tick.
     */
    Mono<TickResult> backfill(@NonNull List<WorkspaceBackfill> workspaces, @NonNull TickBudget budget) {
        var vectors = config.getVectors();
        var embedTimeout = vectors.getEmbedTimeout().toJavaDuration();
        var first = workspaces.stream()
                .filter(workspace -> !workspace.projects().isEmpty())
                .map(workspace -> new Lane(workspace, Run.start(workspace.projects().stream()
                        .sorted(order(vectors.getMaxTracesPerTick())).toList(), workspace.arrival()), false))
                .toList();
        if (first.isEmpty()) {
            return Mono.just(TickResult.EMPTY);
        }
        // Each step is one round, one turn per workspace with work left; expand() runs the rounds one after another.
        return Mono.just(first)
                .expand(lanes -> lanes.stream().anyMatch(Lane::active) && budget.hasTimeForRead(embedTimeout)
                        ? round(lanes, budget, embedTimeout)
                        : Mono.empty())
                .last()
                .flatMap(lanes -> Flux.fromIterable(lanes).filter(lane -> !lane.recorded())
                        .concatMap(this::record)
                        .then(Mono.fromCallable(() -> lanes.stream().map(lane -> lane.run().result())
                                .reduce(TickResult.EMPTY, TickResult::plus))));
    }

    /** A workspace's backfill in a phase, its run so far, and whether its progress is recorded. */
    private record Lane(@NonNull WorkspaceBackfill workspace, @NonNull Run run, boolean recorded) {

        boolean active() {
            return !run.stopped() && !run.queue().isEmpty();
        }
    }

    /** One round: a turn for each workspace with work left, while the tick has time for one; in workspace order. */
    private Mono<List<Lane>> round(List<Lane> lanes, TickBudget budget, Duration embedTimeout) {
        return Flux.fromIterable(lanes)
                .flatMapSequential(lane -> lane.active() && budget.hasTimeForRead(embedTimeout)
                        ? next(lane.workspace(), budget, lane.run()).flatMap(run -> {
                            var after = new Lane(lane.workspace(), run, false);
                            // Its backfill ended this turn: recorded now, not after the other workspaces' turns.
                            return after.active()
                                    ? Mono.just(after)
                                    : record(after).thenReturn(
                                            new Lane(lane.workspace(), run, true));
                        })
                        : Mono.just(lane), config.getVectors().getEmbedConcurrency())
                .collectList();
    }

    /** The run after its first project's turn. */
    private Mono<Run> next(WorkspaceBackfill workspace, TickBudget budget, Run run) {
        var project = run.queue().getFirst();
        return turn(workspace.workspace(), project, workspace.window(), budget, run.settled(), run.arrival())
                .map(turn -> run.after(project, turn));
    }

    private Mono<Void> record(Lane lane) {
        var run = lane.run();
        return Mono.fromRunnable(() -> backfills.record(run.progress()))
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(exception -> {
                    log.warn("Topic vectors backfill progress not recorded, reading them again next tick, "
                            + "workspace '{}', projects '{}'", lane.workspace().window().workspaceId(),
                            run.progress().size(), exception);
                    return Mono.empty();
                })
                .then();
    }

    /**
     * One workspace's backfill so far: the projects still to take a turn, in order, and what the turns did.
     *
     * @param stopped an embed call failed: the service would answer the next project the same way this tick
     */
    @Builder(toBuilder = true)
    private record Run(@NonNull List<TopicBackfill> queue, @NonNull List<TopicBackfillProgress> progress,
            @NonNull Set<VectorKey> settled, @NonNull TickResult result, @NonNull ArrivalSteps arrival,
            boolean stopped) {

        static Run start(List<TopicBackfill> queue, ArrivalSteps arrival) {
            return Run.builder().queue(queue).progress(List.of()).settled(Set.of()).result(TickResult.EMPTY)
                    .arrival(arrival).build();
        }

        /** After {@code project}, the first in the queue, took {@code turn}: it goes first again when it has more. */
        Run after(TopicBackfill project, Turn turn) {
            var rest = queue.subList(1, queue.size());
            var nextQueue = turn.more()
                    ? Stream.concat(Stream.of(project), rest.stream()).toList()
                    : List.copyOf(rest);
            var nextProgress = turn.progress()
                    .map(added -> Stream.concat(progress.stream(), Stream.of(added))
                            .toList())
                    .orElse(progress);
            var nextSettled = new HashSet<>(settled);
            nextSettled.addAll(turn.settled());
            return toBuilder().queue(nextQueue).progress(nextProgress).settled(Set.copyOf(nextSettled))
                    .result(result.plus(turn.result())).arrival(turn.arrival())
                    .stopped(turn.result().skipped().containsKey(SkipReason.EMBED_FAILED)).build();
        }
    }

    /**
     * One project's turn: read its window, embed, write. The project is drained when the read was complete and none of
     * its candidates were left for a later tick ({@link TopicVectorWriter.ChunkWrite#leftForLater}). No progress when there is nothing to record:
     * the read failed, or nothing was written and the project is not drained. More: the read stopped at the cap, none
     * was left and some got a vector or a status row, so another read now finds the next ones.
     *
     * <p>A read can return keys an earlier turn of the tick settled: a replica behind the insert does not see the new
     * vector and status rows yet. They are not embedded again; a read that returns only those writes nothing and ends
     * the project's turns.
     */
    private Mono<Turn> turn(TopicVectorsMetrics.Workspace workspace, TopicBackfill project, TopicVectorsFind window,
            TickBudget budget, Set<VectorKey> settledBefore, ArrivalSteps arrival) {
        var vectors = config.getVectors();
        var workspaceId = project.workspaceId();
        // A project the workspace read just started has no count yet (0): read it in slices too, as it may be large.
        var traces = project.backfillTracesTotal();
        var sliceSize = traces == 0 || traces > vectors.getOneSliceMaxTraces()
                ? vectors.getFind().getSliceSize().toJavaDuration()
                : null;
        return WindowRead.read(dao, window.toBuilder().projectId(project.projectId()).build(), sliceSize, budget,
                vectors.getFind().getMaxExecutionTime().toJavaDuration(), vectors.getEmbedTimeout().toJavaDuration())
                .flatMap(read -> {
                    var candidates = read.candidates().stream()
                            .filter(candidate -> !settledBefore.contains(VectorKey.of(candidate)))
                            .toList();
                    return writer.embedAndWrite(workspace, candidates, budget, null, arrival)
                            .map(written -> {
                                var result = written.result();
                                boolean cut = written.leftForLater(candidates);
                                boolean drained = read.complete() && !cut;
                                // A page of only traces that got a status row moves on too: the next read leaves
                                // them out.
                                boolean more = !read.complete() && !cut && !written.settled().isEmpty();
                                if (!drained && result.written() == 0) {
                                    return new Turn(result, Optional.empty(), more, written.settled(),
                                            written.arrival());
                                }
                                return new Turn(result, Optional.of(TopicBackfillProgress.builder()
                                        .workspaceId(workspaceId)
                                        .projectId(project.projectId())
                                        .drainedAt(drained ? budget.start() : null)
                                        .written(result.written())
                                        .build()), more, written.settled(), written.arrival());
                            });
                })
                .onErrorResume(exception -> {
                    metrics.readFailure("backfill", workspace, exception);
                    log.warn("Topic vectors backfill read failed, reading it again next tick, workspace '{}', project "
                            + "'{}'", workspaceId, project.projectId(), exception);
                    return Mono.just(new Turn(TickResult.EMPTY, Optional.empty(), false, Set.of(), arrival));
                });
    }
}

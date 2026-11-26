package com.comet.opik.domain.topics;

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
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The topic vectors backfill's project discovery: about once per {@code longWindowInterval} per workspace, the
 * projects with done statuses in the backfill window, and how many of their traces have a vector, written as their
 * {@code topic_projects} rows. {@link TopicVectorBackfiller} reads those projects.
 */
@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class TopicVectorDiscovery {

    private final @NonNull @Config("topics") TopicsConfig config;
    private final @NonNull TopicVectorsDAO dao;
    private final @NonNull TopicVectorBackfillService backfills;
    private final @NonNull TopicVectorsMetrics metrics;

    /**
     * The discoveries a tick's budget cut short, resumed by the next tick. Only this pod's: when the ticking pod
     * changes, the new one starts them again from their first slice, which changes no row's drained mark.
     */
    private final Map<String, Discovery> discoveries = new ConcurrentHashMap<>();

    /**
     * Discovers the projects of the workspaces due for it, one at a time: never discovered, or not for
     * {@code longWindowInterval} ({@code topic_workspaces.vectors_checked_at}), those a tick cut short first, then the
     * longest not discovered. When that mark cannot be read, every workspace is due, as for a pod that never ran. Per workspace, two plain aggregates over the backfill
     * window, a slice at a time: the projects with done statuses, and their vector counts. A workspace's rows not
     * checked for twice the interval start again, because the tick did not run for it meanwhile. A workspace no longer
     * enabled loses its discovery in progress.
     *
     * @param lowerBound the backfill window's start
     * @param createdAfter the backfill window's {@code trace_created_at} bound
     */
    Mono<Void> discover(@NonNull List<String> workspaceIds, @NonNull Instant lowerBound,
            @NonNull Instant createdAfter, @NonNull TickBudget budget) {
        return checked(workspaceIds).flatMap(checked -> {
            discoveries.keySet().retainAll(new HashSet<>(workspaceIds));
            var interval = config.getVectors().getLongWindowInterval().toJavaDuration();
            var due = workspaceIds.stream()
                    .filter(id -> Optional.ofNullable(checked.get(id))
                            .map(last -> Duration.between(last, budget.start()).compareTo(interval) >= 0)
                            .orElse(true))
                    .sorted(Comparator.comparing((String id) -> !discoveries.containsKey(id))
                            .thenComparing(id -> checked.getOrDefault(id, Instant.MIN)))
                    .limit(config.getVectors().getMaxDiscoveriesPerTick())
                    .toList();
            long started = System.nanoTime();
            return Flux.fromIterable(due)
                    .concatMap(workspaceId -> discover(workspaceId, lowerBound, createdAfter, budget, interval))
                    .then(Mono.fromRunnable(() -> metrics.phase("discover",
                            Duration.ofNanos(System.nanoTime() - started))));
        });
    }

    private Mono<Map<String, Instant>> checked(List<String> workspaceIds) {
        return Mono.fromCallable(() -> backfills.findChecked(workspaceIds))
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(exception -> {
                    log.warn("Topic vectors discovery marks not read, every workspace is due for discovery, "
                            + "workspaces '{}'", workspaceIds.size(), exception);
                    return Mono.just(Map.of());
                });
    }

    /**
     * One workspace's discovery, a slice at a time while half the tick budget is left; the next tick resumes it. Its
     * rows are written once every slice of both reads is read.
     */
    private Mono<Void> discover(String workspaceId, Instant lowerBound, Instant createdAfter, TickBudget budget,
            Duration interval) {
        return Mono.defer(() -> {
            var reserve = config.getVectors().getEmbedTimeout().toJavaDuration();
            var discoveryReserve = discoveryReserve(budget, reserve);
            if (!budget.hasTimeForRead(discoveryReserve)) {
                return Mono.empty();
            }
            var maxExecutionTime = config.getVectors().getFind().getMaxExecutionTime().toJavaDuration();
            var first = discoveries.computeIfAbsent(workspaceId,
                    __ -> Discovery.start(budget.start(), lowerBound, dao.slices(createdAfter)));
            // Each step reads one slice and keeps the discovery after it, so a tick cut short leaves it to the next.
            return Mono
                    .defer(() -> readSlice(workspaceId, first, budget.readSeconds(maxExecutionTime, discoveryReserve)))
                    .expand(discovery -> !discovery.done() && budget.hasTimeForRead(discoveryReserve)
                            ? Mono.defer(() -> readSlice(workspaceId, discovery,
                                    budget.readSeconds(maxExecutionTime, discoveryReserve)))
                            : Mono.empty())
                    .last()
                    .flatMap(discovery -> discovery.done() ? save(workspaceId, discovery, interval) : Mono.empty())
                    .onErrorResume(exception -> {
                        discoveries.remove(workspaceId);
                        log.warn("Topic vectors project discovery failed, trying again next tick, workspace '{}'",
                                workspaceId, exception);
                        return metrics.workspace(workspaceId)
                                .doOnNext(workspace -> metrics.readFailure("discover", workspace, exception))
                                .then(Mono.empty());
                    });
        });
    }

    /**
     * What a discovery slice leaves for after it: half of the tick budget, and at least what one more read needs. A
     * slice starts only with that plus a read's minimum left, and its {@code max_execution_time} ends before it, so a
     * long discovery (a 365d backfill window is over 100 reads per workspace) leaves the fresh and backfill phases the
     * other half; the next tick resumes it.
     */
    private static Duration discoveryReserve(TickBudget budget, Duration reserve) {
        var half = Duration.between(budget.start(), budget.deadline()).dividedBy(2);
        var oneRead = TickBudget.MIN_READ_TIME.plus(reserve);
        return half.compareTo(oneRead) > 0 ? half : oneRead;
    }

    /** The next slice: the projects' slices first, then their vector counts. The discovery after it is kept. */
    private Mono<Discovery> readSlice(String workspaceId, Discovery discovery, long maxExecutionSeconds) {
        var read = !discovery.toFind().isEmpty()
                ? dao.findBackfillProjects(workspaceId, TopicEmbedding.SUMMARY_TASK, discovery.lowerBound(),
                        discovery.toFind().getFirst(), maxExecutionSeconds)
                        .collectList()
                        .map(discovery::afterFound)
                : dao.countTracesWithVectorsByProject(workspaceId, TopicEmbedding.SUMMARY_TASK,
                        discovery.toCount().getFirst(), List.copyOf(discovery.projects().keySet()),
                        maxExecutionSeconds)
                        .map(discovery::afterCounted);
        return read.doOnNext(next -> discoveries.put(workspaceId, next));
    }

    private Mono<Void> save(String workspaceId, Discovery discovery, Duration interval) {
        var start = discovery.start();
        var rows = discovery.projects().values().stream()
                .map(project -> TopicBackfill.builder()
                        .workspaceId(workspaceId)
                        .projectId(project.projectId())
                        .backfillStartedAt(start)
                        .backfillLastDoneAt(project.lastDoneAt())
                        .backfillTracesTotal(project.traces())
                        .backfillTracesDone(Math.min(project.traces(),
                                discovery.withVectors().getOrDefault(project.projectId(), 0L)))
                        .build())
                .toList();
        return Mono
                .fromCallable(
                        () -> backfills.discovered(workspaceId, rows, start, start.minus(interval.multipliedBy(2))))
                .subscribeOn(Schedulers.boundedElastic())
                .doOnNext(removed -> {
                    if (removed > 0) {
                        log.info("Topic vectors backfill rows of deleted projects removed, workspace '{}', rows "
                                + "'{}'", workspaceId, removed);
                    }
                    discoveries.remove(workspaceId);
                })
                .then();
    }

    /**
     * A workspace's discovery so far: the slices each read has left, and what the slices read so far found. Its bounds
     * and start are those of the tick that began it.
     */
    private record Discovery(@NonNull Instant start, @NonNull Instant lowerBound,
            @NonNull List<TopicVectorsDAO.Slice> toFind, @NonNull List<TopicVectorsDAO.Slice> toCount,
            @NonNull Map<UUID, TopicVectorsDAO.BackfillProject> projects, @NonNull Map<UUID, Long> withVectors) {

        static Discovery start(Instant start, Instant lowerBound, List<TopicVectorsDAO.Slice> slices) {
            return new Discovery(start, lowerBound, List.copyOf(slices), List.copyOf(slices), Map.of(), Map.of());
        }

        boolean done() {
            return toFind.isEmpty() && toCount.isEmpty();
        }

        Discovery afterFound(List<TopicVectorsDAO.BackfillProject> found) {
            var merged = new HashMap<>(projects);
            found.forEach(project -> merged.merge(project.projectId(), project,
                    TopicVectorsDAO.BackfillProject::merge));
            return new Discovery(start, lowerBound, toFind.subList(1, toFind.size()), toCount, Map.copyOf(merged),
                    withVectors);
        }

        Discovery afterCounted(Map<UUID, Long> counts) {
            var merged = new HashMap<>(withVectors);
            counts.forEach((projectId, traces) -> merged.merge(projectId, traces, Long::sum));
            return new Discovery(start, lowerBound, toFind, toCount.subList(1, toCount.size()), projects,
                    Map.copyOf(merged));
        }
    }
}

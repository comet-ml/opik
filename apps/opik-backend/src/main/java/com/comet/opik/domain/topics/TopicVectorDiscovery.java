package com.comet.opik.domain.topics;

import com.comet.opik.domain.topics.TopicVectorsMetrics.DiscoveryOutcome;
import com.comet.opik.infrastructure.TopicsConfig;
import com.google.common.collect.Lists;
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
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

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
     * The workspaces due for project discovery at {@code start}, at most {@code maxDiscoveriesPerTick}: never
     * discovered, or not for {@code longWindowInterval} ({@code topic_workspaces.vectors_checked_at}), the longest not
     * discovered first. When that mark cannot be read, every workspace is due, as for a pod that never ran.
     */
    Mono<List<String>> due(@NonNull List<String> workspaceIds, @NonNull Instant start) {
        return checked(workspaceIds).map(checked -> {
            var interval = config.getVectors().getLongWindowInterval().toJavaDuration();
            return workspaceIds.stream()
                    .filter(id -> Optional.ofNullable(checked.get(id))
                            .map(last -> Duration.between(last, start).compareTo(interval) >= 0)
                            .orElse(true))
                    .sorted(Comparator.comparing((String id) -> checked.getOrDefault(id, Instant.MIN)))
                    .limit(config.getVectors().getMaxDiscoveriesPerTick())
                    .toList();
        });
    }

    /**
     * Discovers the projects of the {@link #due} workspaces, one at a time. Per workspace, two plain aggregates over
     * the backfill window, a slice at a time: the projects with done statuses, and their vector counts. A discovery
     * must finish within this tick: one the budget cuts short writes nothing, keeps the workspace due and starts again
     * from its first slice next tick. A workspace's rows not checked for twice the interval start again, because the
     * tick did not run for it meanwhile.
     *
     * @param lowerBound the backfill window's start
     * @param createdAfter the backfill window's {@code trace_created_at} bound
     */
    Mono<Void> discover(@NonNull List<String> due, @NonNull Instant lowerBound, @NonNull Instant createdAfter,
            @NonNull TickBudget budget) {
        return Mono.defer(() -> {
            var interval = config.getVectors().getLongWindowInterval().toJavaDuration();
            var reserve = discoveryReserve(budget, config.getVectors().getEmbedTimeout().toJavaDuration(),
                    config.getVectors().getDiscoveryMinShare());
            long started = System.nanoTime();
            return Flux.fromIterable(due)
                    .concatMap(workspaceId -> discover(workspaceId, lowerBound, createdAfter, budget, reserve,
                            interval))
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
     * One workspace's discovery, a slice at a time while more than {@code discoveryReserve} is left. Its rows are
     * written once every slice of both reads is read; a discovery cut short before that is dropped.
     */
    private Mono<Void> discover(String workspaceId, Instant lowerBound, Instant createdAfter, TickBudget budget,
            Duration discoveryReserve, Duration interval) {
        return Mono.defer(() -> {
            if (!budget.hasTimeForRead(discoveryReserve)) {
                return Mono.empty();
            }
            var maxExecutionTime = config.getVectors().getFind().getMaxExecutionTime().toJavaDuration();
            var first = Discovery.start(budget.start(), lowerBound, dao.slices(createdAfter));
            // Each step reads one slice and gives the discovery after it; expand() runs the steps one after another.
            return Mono
                    .defer(() -> readSlice(workspaceId, first, budget, maxExecutionTime, discoveryReserve))
                    .expand(discovery -> !discovery.done() && budget.hasTimeForRead(discoveryReserve)
                            ? Mono.defer(() -> readSlice(workspaceId, discovery, budget, maxExecutionTime,
                                    discoveryReserve))
                            : Mono.empty())
                    .last()
                    .flatMap(discovery -> discovery.done()
                            ? save(workspaceId, discovery, interval).thenReturn(DiscoveryOutcome.COMPLETE)
                            : Mono.fromRunnable(() -> log.info("Topic vectors project discovery cut short by the "
                                    + "tick budget, starting it again next tick, workspace '{}', slices left '{}'",
                                    workspaceId, discovery.toFind().size() + discovery.toCount().size()))
                                    .thenReturn(DiscoveryOutcome.CUT))
                    .flatMap(outcome -> metrics.workspace(workspaceId)
                            .doOnNext(workspace -> metrics.discovery(workspace, outcome)))
                    .onErrorResume(exception -> {
                        log.warn("Topic vectors project discovery failed, trying again next tick, workspace '{}'",
                                workspaceId, exception);
                        return metrics.workspace(workspaceId)
                                .doOnNext(workspace -> {
                                    metrics.readFailure("discover", workspace, exception);
                                    metrics.discovery(workspace, DiscoveryOutcome.FAILED);
                                });
                    })
                    .then();
        });
    }

    /**
     * What the fresh phase leaves to the discovery on a tick with a workspace due for it: {@code discoveryMinShare} of
     * the budget, or nothing when that share is too short for the discovery to start a read and keep its reserve.
     */
    Duration share(@NonNull TickBudget budget) {
        var vectors = config.getVectors();
        var share = Duration.ofMillis(Math.round(budget.total().toMillis() * vectors.getDiscoveryMinShare()));
        var oneRead = TickBudget.MIN_READ_TIME.plus(vectors.getEmbedTimeout().toJavaDuration());
        return share.compareTo(TickBudget.MIN_READ_TIME.plus(oneRead)) >= 0 ? share : Duration.ZERO;
    }

    /**
     * What the discovery phase leaves for the backfill after it, decided when the phase starts: the time left less
     * {@code minShare} of the tick budget, so discovery gets at least that share after a busy fresh phase; at most half
     * the budget, so discovery takes no more than half; at least what one more read needs. A slice starts only with
     * that plus a read's minimum left, and its {@code max_execution_time} ends before it.
     */
    static Duration discoveryReserve(TickBudget budget, Duration embedTimeout, double minShare) {
        var half = budget.total().dividedBy(2);
        var oneRead = TickBudget.MIN_READ_TIME.plus(embedTimeout);
        var afterShare = budget.left().minus(Duration.ofMillis(Math.round(budget.total().toMillis() * minShare)));
        var reserve = afterShare.compareTo(half) > 0 ? half : afterShare;
        return reserve.compareTo(oneRead) > 0 ? reserve : oneRead;
    }

    /**
     * The next slice: the projects' slices first, then their vector counts, for {@code maxExcludedProjects} project ids
     * per read, so a read's id list stays bounded. Each count read starts only with time left for it; a slice whose
     * counts the budget stops is returned as not read, so the discovery is cut short.
     */
    private Mono<Discovery> readSlice(String workspaceId, Discovery discovery, TickBudget budget,
            Duration maxExecutionTime, Duration discoveryReserve) {
        if (!discovery.toFind().isEmpty()) {
            return dao.findBackfillProjects(workspaceId, TopicEmbedding.SUMMARY_TASK, discovery.lowerBound(),
                    discovery.toFind().getFirst(), budget.readSeconds(maxExecutionTime, discoveryReserve))
                    .collectList()
                    .map(discovery::afterFound);
        }
        var slice = discovery.toCount().getFirst();
        var chunks = Lists.partition(List.copyOf(discovery.projects().keySet()),
                config.getVectors().getMaxExcludedProjects());
        return Flux.fromIterable(chunks)
                .concatMap(projectIds -> Mono.defer(() -> budget.hasTimeForRead(discoveryReserve)
                        ? dao.countTracesWithVectorsByProject(workspaceId, TopicEmbedding.SUMMARY_TASK, slice,
                                projectIds, budget.readSeconds(maxExecutionTime, discoveryReserve))
                                .map(Optional::of)
                        : Mono.just(Optional.<Map<UUID, Long>>empty())))
                .takeUntil(Optional::isEmpty)
                .collectList()
                .map(counts -> counts.stream().anyMatch(Optional::isEmpty)
                        ? discovery
                        : discovery.afterCounted(counts.stream().flatMap(count -> count.orElseThrow().entrySet()
                                .stream()).collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue))));
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
                })
                .then();
    }

    /**
     * A workspace's discovery so far: the slices each read has left, and what the slices read so far found. Its bounds
     * and start are those of the tick that began it.
     */
    @Builder(toBuilder = true)
    private record Discovery(@NonNull Instant start, @NonNull Instant lowerBound,
            @NonNull List<TopicVectorsDAO.Slice> toFind, @NonNull List<TopicVectorsDAO.Slice> toCount,
            @NonNull Map<UUID, TopicVectorsDAO.BackfillProject> projects, @NonNull Map<UUID, Long> withVectors) {

        static Discovery start(Instant start, Instant lowerBound, List<TopicVectorsDAO.Slice> slices) {
            return Discovery.builder().start(start).lowerBound(lowerBound).toFind(List.copyOf(slices))
                    .toCount(List.copyOf(slices)).projects(Map.of()).withVectors(Map.of()).build();
        }

        boolean done() {
            return toFind.isEmpty() && toCount.isEmpty();
        }

        Discovery afterFound(List<TopicVectorsDAO.BackfillProject> found) {
            var merged = new HashMap<>(projects);
            found.forEach(project -> merged.merge(project.projectId(), project,
                    TopicVectorsDAO.BackfillProject::merge));
            return toBuilder().toFind(toFind.subList(1, toFind.size())).projects(Map.copyOf(merged)).build();
        }

        Discovery afterCounted(Map<UUID, Long> counts) {
            var merged = new HashMap<>(withVectors);
            counts.forEach((projectId, traces) -> merged.merge(projectId, traces, Long::sum));
            return toBuilder().toCount(toCount.subList(1, toCount.size())).withVectors(Map.copyOf(merged)).build();
        }
    }
}

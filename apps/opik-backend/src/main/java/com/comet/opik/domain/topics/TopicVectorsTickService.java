package com.comet.opik.domain.topics;

import com.comet.opik.domain.ailabels.AiLabelTask;
import com.comet.opik.domain.ailabels.AiLabelTaskRegistry;
import com.comet.opik.domain.topics.TopicVectorsDAO.Candidate;
import com.comet.opik.infrastructure.AiLabelsConfig;
import com.comet.opik.infrastructure.TopicsConfig;
import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One topic vectors tick: for every workspace topics and ai-labels are enabled for, find the traces whose latest
 * {@code trace_summary} status is done and that have no vector yet, embed their summaries and store the unit-length
 * vectors in {@code topic_vectors}. Runs on the leader only.
 *
 * <p>One workspace at a time: one read of the done statuses written in {@code window}, up to {@code maxTracesPerTick},
 * oldest first, with their summaries; then {@link TopicEmbedder} embeds them chunk by chunk and each chunk's vectors are
 * written as it arrives. A workspace is read only with time left for the read and one embed call, so the tick never
 * spends its budget reading what it cannot embed.
 *
 * <p>ClickHouse alone says which traces have no vector: every read is the done statuses less the keys already in
 * {@code topic_vectors}, so a repeated, overlapping or late tick changes nothing. Each workspace's read starts at its
 * watermark in {@code topic_workspaces}, less {@link #WATERMARK_OVERLAP}, and never before the window: the tick moves it
 * past the statuses it gave a vector and never past one it found and left, so a leader change loses nothing. Work per
 * tick is bounded per workspace by {@code maxTracesPerTick} and in total by the tick budget; what is left is found
 * again by the next tick. Workspaces are visited in a new random order every tick, so when the budget does not reach
 * them all, the ones left out differ from tick to tick.
 */
@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class TopicVectorsTickService {

    private final @NonNull @Config("topics") TopicsConfig config;
    private final @NonNull @Config("aiLabels") AiLabelsConfig aiLabelsConfig;
    private final @NonNull TopicWorkspaceService workspaces;
    private final @NonNull TopicVectorsDAO dao;
    private final @NonNull AiLabelTaskRegistry registry;
    private final @NonNull TopicEmbedder embedder;
    private final @NonNull TopicVectorsMetrics metrics;

    /**
     * How far before its watermark a workspace's read starts. ClickHouse stamps a status by its own clock and before
     * the insert is visible, so one stamped just before a read's upper bound can show up only after the read.
     */
    static final Duration WATERMARK_OVERLAP = Duration.ofMinutes(5);

    /**
     * Fails with {@link IllegalStateException} when ai-labels has no {@code trace_summary} task: no tick can run, so the
     * job counts the tick as failed rather than as one that ran and found nothing.
     */
    public Mono<TickResult> tick(@NonNull Instant start) {
        return Mono.defer(() -> {
            var vectors = config.getVectors();
            var task = registry.find(TopicEmbedding.SUMMARY_TASK).orElseThrow(() -> new IllegalStateException(
                    "ai-labels has no task '%s'".formatted(TopicEmbedding.SUMMARY_TASK)));
            var windowStart = lowerBound(earliestAppliesFrom(task), start, vectors.getWindow().toJavaDuration());
            if (windowStart.isAfter(start)) {
                return Mono.just(TickResult.EMPTY);
            }
            var budget = TickBudget.of(start, vectors.getTickBudget().toJavaDuration(), Clock.systemUTC());
            return Mono.fromCallable(() -> {
                var workspaceIds = workspaces.enabledWorkspaceIds();
                return new Enabled(shuffled(workspaceIds), workspaces.vectorsWatermarks(workspaceIds));
            })
                    .subscribeOn(Schedulers.boundedElastic())
                    .flatMapMany(enabled -> Flux.fromIterable(enabled.workspaceIds())
                            .concatMap(workspaceId -> visit(workspaceId,
                                    readFrom(windowStart, enabled.watermarks().get(workspaceId)), start, budget)))
                    .collectList()
                    .flatMap(this::finish);
        });
    }

    private record Enabled(List<String> workspaceIds, Map<String, Instant> watermarks) {
    }

    /** One workspace's turn: its window read from {@code lowerBound}, then its embed calls and writes. */
    private Mono<Visit> visit(String workspaceId, Instant lowerBound, Instant start, TickBudget budget) {
        return metrics.workspace(workspaceId).flatMap(workspace -> {
            var vectors = config.getVectors();
            var embedTimeout = vectors.getEmbedTimeout().toJavaDuration();
            if (!budget.hasTimeForRead(embedTimeout)) {
                return Mono.just(Visit.notRead(workspace));
            }
            var find = TopicVectorsFind.builder()
                    .workspaceId(workspaceId)
                    .task(TopicEmbedding.SUMMARY_TASK)
                    .lowerBound(lowerBound)
                    .upperBound(start)
                    .createdAfter(createdAfter(lowerBound, aiLabelsConfig.getHorizon().toJavaDuration(),
                            vectors.getFind().getCreatedAtSlack().toJavaDuration()))
                    .limit(vectors.getMaxTracesPerTick())
                    .maxExecutionSeconds(budget.readSeconds(vectors.getFind().getMaxExecutionTime().toJavaDuration(),
                            embedTimeout))
                    .build();
            long readStarted = System.nanoTime();
            return dao.findUnvectored(find)
                    .collectList()
                    .map(Optional::of)
                    .onErrorResume(exception -> {
                        metrics.readFailure("find", workspace, exception);
                        log.warn("Topic vectors window read failed, reading it again next tick, workspace '{}'",
                                workspaceId, exception);
                        return Mono.just(Optional.empty());
                    })
                    .flatMap(found -> {
                        var read = since(readStarted);
                        if (found.isEmpty()) {
                            return Mono.just(new Visit(workspace, true, null, TickResult.EMPTY, read, Duration.ZERO));
                        }
                        long embedStarted = System.nanoTime();
                        Set<VectorKey> written = ConcurrentHashMap.newKeySet();
                        return embedAndWrite(workspace, found.get(), written, budget)
                                .map(result -> new Visit(workspace, true,
                                        watermark(found.get(), written, find.limit(), start), result, read,
                                        since(embedStarted)));
                    });
        });
    }

    private Mono<TickResult> embedAndWrite(TopicVectorsMetrics.Workspace workspace, List<Candidate> found,
            Set<VectorKey> written, TickBudget budget) {
        return embedder.embed(workspace, found, budget)
                .concatMap(outcome -> write(workspace, outcome, written))
                .reduce(TickResult.EMPTY, TickResult::plus);
    }

    private Mono<TickResult> write(TopicVectorsMetrics.Workspace workspace, ChunkOutcome outcome,
            Set<VectorKey> written) {
        return Mono.defer(() -> {
            outcome.skipped().forEach((reason, traces) -> metrics.skipped(workspace, reason, traces));
            var skipped = new TickResult(0, outcome.skipped());
            var vectors = outcome.vectors();
            if (vectors.isEmpty()) {
                return Mono.just(skipped);
            }
            return dao.insert(vectors)
                    .then(Mono.fromCallable(() -> {
                        vectors.forEach(vector -> written.add(VectorKey.of(vector)));
                        metrics.written(workspace, vectors.size());
                        return skipped.plus(TickResult.written(vectors.size()));
                    }))
                    .onErrorResume(exception -> {
                        log.warn("Topic vectors insert failed, embedding them again next tick, workspace '{}', rows "
                                + "'{}'", workspace.id(), vectors.size(), exception);
                        metrics.skipped(workspace, SkipReason.INSERT_FAILED, vectors.size());
                        return Mono.just(skipped.plus(TickResult.skipped(SkipReason.INSERT_FAILED, vectors.size())));
                    });
        });
    }

    private Mono<TickResult> finish(List<Visit> visits) {
        var watermarks = new HashMap<String, Instant>();
        var notRead = new ArrayList<TopicVectorsMetrics.Workspace>();
        var result = TickResult.EMPTY;
        var read = Duration.ZERO;
        var embed = Duration.ZERO;
        for (var visit : visits) {
            if (visit.watermark() != null) {
                watermarks.put(visit.workspace().id(), visit.watermark());
            }
            if (!visit.reached()) {
                notRead.add(visit.workspace());
            }
            result = result.plus(visit.result());
            read = read.plus(visit.readTime());
            embed = embed.plus(visit.embedTime());
        }
        if (!notRead.isEmpty()) {
            metrics.workspacesNotRead(notRead);
            log.warn("Topic vectors tick ran out of budget, workspaces not read '{}' of '{}'", notRead.size(),
                    visits.size());
        }
        metrics.phase("find", read);
        metrics.phase("embed", embed);
        var tickResult = result;
        return Mono.fromRunnable(() -> workspaces.setVectorsWatermarks(watermarks))
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(exception -> {
                    // Only costs a wider read next tick: the old watermarks stay behind the new ones.
                    log.warn("Topic vectors watermarks not saved, workspaces '{}'", watermarks.size(), exception);
                    return Mono.empty();
                })
                .thenReturn(tickResult);
    }

    /**
     * One workspace's part of a tick. {@code reached} is false when the budget left no time to read it;
     * {@code watermark} is its next one, null when not reached or its read failed.
     */
    private record Visit(@NonNull TopicVectorsMetrics.Workspace workspace, boolean reached,
            @Nullable Instant watermark, @NonNull TickResult result, @NonNull Duration readTime,
            @NonNull Duration embedTime) {

        static Visit notRead(TopicVectorsMetrics.Workspace workspace) {
            return new Visit(workspace, false, null, TickResult.EMPTY, Duration.ZERO, Duration.ZERO);
        }
    }

    /** A {@code topic_vectors} row's key within a workspace's read. */
    record VectorKey(@NonNull UUID projectId, @NonNull UUID traceId, int taskVersion) {

        static VectorKey of(TopicVector vector) {
            return new VectorKey(vector.projectId(), vector.traceId(), vector.taskVersion());
        }

        static VectorKey of(Candidate candidate) {
            return new VectorKey(candidate.projectId(), candidate.traceId(), candidate.taskVersion());
        }
    }

    /**
     * A workspace's next watermark after a read that found {@code found}, oldest status first, and wrote
     * {@code written}: the status of the first one it found and did not write, so the next read finds it again; with
     * none, the last status found when the read hit {@code limit}, as more may share it, and else the read's upper bound.
     */
    static Instant watermark(@NonNull List<Candidate> found, @NonNull Set<VectorKey> written, int limit,
            @NonNull Instant upperBound) {
        return found.stream()
                .filter(candidate -> !written.contains(VectorKey.of(candidate)))
                .findFirst()
                .map(Candidate::statusUpdatedAt)
                .orElseGet(() -> found.size() < limit ? upperBound : found.getLast().statusUpdatedAt());
    }

    /** A workspace's read starts {@link #WATERMARK_OVERLAP} before its watermark, never before the window. */
    static Instant readFrom(@NonNull Instant windowStart, @Nullable Instant watermark) {
        if (watermark == null) {
            return windowStart;
        }
        var fromWatermark = watermark.minus(WATERMARK_OVERLAP);
        return fromWatermark.isAfter(windowStart) ? fromWatermark : windowStart;
    }

    private static Duration since(long startedNanos) {
        return Duration.ofNanos(System.nanoTime() - startedNanos);
    }

    private static <T> List<T> shuffled(List<T> items) {
        var shuffled = new ArrayList<>(items);
        Collections.shuffle(shuffled);
        return shuffled;
    }

    /** Never before the task first applied, so no run scans statuses from before the task existed. */
    static Instant lowerBound(@NonNull Instant appliesFrom, @NonNull Instant now, @NonNull Duration window) {
        var windowStart = now.minus(window);
        return windowStart.isAfter(appliesFrom) ? windowStart : appliesFrom;
    }

    static Instant earliestAppliesFrom(@NonNull AiLabelTask task) {
        return task.versions().stream().map(AiLabelTask.Version::appliesFrom).min(Comparator.naturalOrder())
                .orElseThrow();
    }

    /**
     * The window read's {@code trace_created_at} bound: ai-labels labels only traces created within its horizon, so a
     * status written at or after {@code lowerBound} belongs to a trace created at or after {@code lowerBound - horizon};
     * {@code slack} covers the time from the sweep to the status write and a horizon changed since.
     */
    static Instant createdAfter(@NonNull Instant lowerBound, @NonNull Duration horizon, @NonNull Duration slack) {
        return lowerBound.minus(horizon).minus(slack);
    }
}

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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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
 * watermark in {@code topic_vector_watermarks}, less {@code watermarkOverlap}, and never before the window. The tick
 * moves it past the statuses it gave a vector or never will (a refused input, a zero vector), never past one it left
 * for a later try, and saves it when the workspace's turn ends, so a cancelled tick keeps what its finished turns did.
 * A status that becomes visible more than {@code watermarkOverlap} after a later one was read is left to the daily
 * pass, which reads the whole long window. Work per
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
                                    readFrom(windowStart, enabled.watermarks().get(workspaceId),
                                            vectors.getWatermarkOverlap().toJavaDuration()),
                                    start, budget)))
                    .collectList()
                    .flatMap(this::finish);
        });
    }

    private record Enabled(@NonNull List<String> workspaceIds, @NonNull Map<String, Instant> watermarks) {
    }

    /**
     * One workspace's turn: its window read from {@code lowerBound}, then its embed calls and writes, then its new
     * watermark saved. Without time left for the read, it skips the workspace name lookup too.
     */
    private Mono<Visit> visit(String workspaceId, Instant lowerBound, Instant start, TickBudget budget) {
        var embedTimeout = config.getVectors().getEmbedTimeout().toJavaDuration();
        if (!budget.hasTimeForRead(embedTimeout)) {
            return Mono.just(Visit.notRead(TopicVectorsMetrics.Workspace.unnamed(workspaceId)));
        }
        return metrics.workspace(workspaceId).flatMap(workspace -> {
            var vectors = config.getVectors();
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
                            return Mono.just(new Visit(workspace, true, TickResult.EMPTY, read, Duration.ZERO));
                        }
                        long embedStarted = System.nanoTime();
                        return embedAndWrite(workspace, found.get(), budget)
                                .flatMap(written -> {
                                    var embed = since(embedStarted);
                                    var watermark = watermark(found.get(), written.settled(), find.limit(), start);
                                    return saveWatermark(workspaceId, watermark)
                                            .thenReturn(new Visit(workspace, true, written.result(), read, embed));
                                });
                    });
        });
    }

    /** One small write per workspace, as its turn ends: a tick cancelled later keeps the workspaces it finished. */
    private Mono<Void> saveWatermark(String workspaceId, Instant watermark) {
        return Mono.<Void>fromRunnable(() -> workspaces.setVectorsWatermark(workspaceId, watermark))
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(exception -> {
                    // Only costs a wider read next tick: the old watermark stays behind the new one.
                    log.warn("Topic vectors watermark not saved, workspace '{}'", workspaceId, exception);
                    return Mono.empty();
                });
    }

    private Mono<ChunkWrite> embedAndWrite(TopicVectorsMetrics.Workspace workspace, List<Candidate> found,
            TickBudget budget) {
        return embedder.embed(workspace, found, budget)
                .concatMap(outcome -> write(workspace, outcome))
                .reduce(ChunkWrite.EMPTY, ChunkWrite::plus);
    }

    /** The chunk's traces no later tick would embed are settled whether or not its insert succeeds. */
    private Mono<ChunkWrite> write(TopicVectorsMetrics.Workspace workspace, ChunkOutcome outcome) {
        return Mono.defer(() -> {
            outcome.skipped().forEach((reason, traces) -> metrics.skipped(workspace, reason, traces));
            var skipped = new TickResult(0, outcome.skipped());
            var vectors = outcome.vectors();
            if (vectors.isEmpty()) {
                return Mono.just(new ChunkWrite(skipped, outcome.settled()));
            }
            return dao.insert(vectors)
                    .then(Mono.fromCallable(() -> {
                        metrics.written(workspace, vectors.size());
                        var settled = new HashSet<>(outcome.settled());
                        vectors.forEach(vector -> settled.add(VectorKey.of(vector)));
                        return new ChunkWrite(skipped.plus(TickResult.written(vectors.size())), settled);
                    }))
                    .onErrorResume(exception -> {
                        log.warn("Topic vectors insert failed, embedding them again next tick, workspace '{}', rows "
                                + "'{}'", workspace.id(), vectors.size(), exception);
                        metrics.skipped(workspace, SkipReason.INSERT_FAILED, vectors.size());
                        return Mono.just(new ChunkWrite(
                                skipped.plus(TickResult.skipped(SkipReason.INSERT_FAILED, vectors.size())),
                                outcome.settled()));
                    });
        });
    }

    private Mono<TickResult> finish(List<Visit> visits) {
        var notRead = new ArrayList<TopicVectorsMetrics.Workspace>();
        var result = TickResult.EMPTY;
        var read = Duration.ZERO;
        var embed = Duration.ZERO;
        for (var visit : visits) {
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
        return Mono.just(result);
    }

    /** One workspace's part of a tick. {@code reached} is false when the budget left no time to read it. */
    private record Visit(@NonNull TopicVectorsMetrics.Workspace workspace, boolean reached,
            @NonNull TickResult result, @NonNull Duration readTime, @NonNull Duration embedTime) {

        static Visit notRead(TopicVectorsMetrics.Workspace workspace) {
            return new Visit(workspace, false, TickResult.EMPTY, Duration.ZERO, Duration.ZERO);
        }
    }

    /** What writing one chunk produced: its counts, and the keys of its traces the watermark may pass. */
    private record ChunkWrite(@NonNull TickResult result, @NonNull Set<VectorKey> settled) {

        static final ChunkWrite EMPTY = new ChunkWrite(TickResult.EMPTY, Set.of());

        ChunkWrite plus(@NonNull ChunkWrite other) {
            var union = new HashSet<>(settled);
            union.addAll(other.settled);
            return new ChunkWrite(result.plus(other.result), union);
        }
    }

    /**
     * A workspace's next watermark after a read that found {@code found}, oldest status first, and settled
     * {@code settled} (written, or never to be embedded): the status of the first one it found and did not settle, so the
     * next read finds it again; with none, the last status found when the read hit {@code limit}, as more may share it,
     * and else the read's upper bound.
     */
    static Instant watermark(@NonNull List<Candidate> found, @NonNull Set<VectorKey> settled, int limit,
            @NonNull Instant upperBound) {
        return found.stream()
                .filter(candidate -> !settled.contains(VectorKey.of(candidate)))
                .findFirst()
                .map(Candidate::statusUpdatedAt)
                .orElseGet(() -> found.size() < limit ? upperBound : found.getLast().statusUpdatedAt());
    }

    /** A workspace's read starts {@code overlap} before its watermark, never before the window. */
    static Instant readFrom(@NonNull Instant windowStart, @Nullable Instant watermark, @NonNull Duration overlap) {
        if (watermark == null) {
            return windowStart;
        }
        var fromWatermark = watermark.minus(overlap);
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

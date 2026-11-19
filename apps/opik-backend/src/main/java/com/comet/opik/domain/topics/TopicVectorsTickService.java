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
import java.util.Optional;

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
 * {@code topic_vectors}, so a repeated, overlapping or late tick changes nothing, and a leader change loses nothing.
 * Work per tick is bounded per workspace by {@code maxTracesPerTick} and in total by the tick budget; what is left is
 * found again by the next tick. Workspaces are visited in a new random order every tick, so when the budget does not
 * reach them all, the ones left out differ from tick to tick.
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
            var lowerBound = lowerBound(earliestAppliesFrom(task), start, vectors.getWindow().toJavaDuration());
            if (lowerBound.isAfter(start)) {
                return Mono.just(TickResult.EMPTY);
            }
            var find = TopicVectorsFind.builder()
                    .task(TopicEmbedding.SUMMARY_TASK)
                    .lowerBound(lowerBound)
                    .upperBound(start)
                    .createdAfter(createdAfter(lowerBound, aiLabelsConfig.getHorizon().toJavaDuration(),
                            vectors.getFind().getCreatedAtSlack().toJavaDuration()))
                    .limit(vectors.getMaxTracesPerTick());
            var budget = TickBudget.of(start, vectors.getTickBudget().toJavaDuration(), Clock.systemUTC());
            return Mono.fromCallable(workspaces::enabledWorkspaceIds)
                    .subscribeOn(Schedulers.boundedElastic())
                    .flatMapMany(workspaceIds -> Flux.fromIterable(shuffled(workspaceIds)))
                    .concatMap(workspaceId -> visit(workspaceId, find, budget))
                    .collectList()
                    .map(this::finish);
        });
    }

    /** One workspace's turn: its window read, then its embed calls and writes. */
    private Mono<Visit> visit(String workspaceId, TopicVectorsFind.TopicVectorsFindBuilder find, TickBudget budget) {
        return Mono.defer(() -> {
            var embedTimeout = config.getVectors().getEmbedTimeout().toJavaDuration();
            if (!budget.hasTimeForRead(embedTimeout)) {
                return Mono.just(Visit.notRead(workspaceId));
            }
            var maxExecutionTime = config.getVectors().getFind().getMaxExecutionTime().toJavaDuration();
            long readStarted = System.nanoTime();
            return dao.findUnvectored(find.workspaceId(workspaceId)
                    .maxExecutionSeconds(budget.readSeconds(maxExecutionTime, embedTimeout))
                    .build())
                    .collectList()
                    .map(Optional::of)
                    .onErrorResume(exception -> {
                        metrics.readFailure("find", workspaceId, exception);
                        log.warn("Topic vectors window read failed, workspace '{}'; reading it again next tick",
                                workspaceId, exception);
                        return Mono.just(Optional.empty());
                    })
                    .flatMap(found -> {
                        var read = since(readStarted);
                        if (found.isEmpty()) {
                            return Mono.just(new Visit(workspaceId, true, null, TickResult.EMPTY, read, Duration.ZERO));
                        }
                        long embedStarted = System.nanoTime();
                        return embedAndWrite(workspaceId, found.get(), budget)
                                .map(result -> new Visit(workspaceId, true, oldest(found.get()), result, read,
                                        since(embedStarted)));
                    });
        });
    }

    private Mono<TickResult> embedAndWrite(String workspaceId, List<Candidate> found, TickBudget budget) {
        return embedder.embed(workspaceId, found, budget)
                .concatMap(outcome -> write(workspaceId, outcome))
                .reduce(TickResult.EMPTY, TickResult::plus);
    }

    private Mono<TickResult> write(String workspaceId, ChunkOutcome outcome) {
        return Mono.defer(() -> {
            outcome.skipped().forEach((reason, traces) -> metrics.skipped(workspaceId, reason, traces));
            var skipped = new TickResult(0, outcome.skipped());
            var vectors = outcome.vectors();
            if (vectors.isEmpty()) {
                return Mono.just(skipped);
            }
            return dao.insert(vectors)
                    .then(Mono.fromCallable(() -> {
                        metrics.written(workspaceId, vectors.size());
                        return skipped.plus(TickResult.written(vectors.size()));
                    }))
                    .onErrorResume(exception -> {
                        log.warn("Topic vectors insert failed, workspace '{}', rows '{}'; embedding them again next "
                                + "tick", workspaceId, vectors.size(), exception);
                        metrics.skipped(workspaceId, SkipReason.INSERT_FAILED, vectors.size());
                        return Mono.just(skipped.plus(TickResult.skipped(SkipReason.INSERT_FAILED, vectors.size())));
                    });
        });
    }

    private TickResult finish(List<Visit> visits) {
        var oldest = new HashMap<String, Instant>();
        var notRead = new ArrayList<String>();
        var result = TickResult.EMPTY;
        var read = Duration.ZERO;
        var embed = Duration.ZERO;
        for (var visit : visits) {
            if (visit.oldest() != null) {
                oldest.put(visit.workspaceId(), visit.oldest());
            }
            if (!visit.reached()) {
                notRead.add(visit.workspaceId());
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
        metrics.oldest(oldest);
        metrics.phase("find", read);
        metrics.phase("embed", embed);
        return result;
    }

    /**
     * One workspace's part of a tick. {@code reached} is false when the budget left no time to read it; {@code oldest}
     * is its oldest done status without a vector, {@link Instant#MAX} with none, null when not reached or its read
     * failed.
     */
    private record Visit(@NonNull String workspaceId, boolean reached, @Nullable Instant oldest,
            @NonNull TickResult result, @NonNull Duration readTime, @NonNull Duration embedTime) {

        static Visit notRead(String workspaceId) {
            return new Visit(workspaceId, false, null, TickResult.EMPTY, Duration.ZERO, Duration.ZERO);
        }
    }

    private static Instant oldest(List<Candidate> found) {
        return found.stream().map(Candidate::statusUpdatedAt).min(Comparator.naturalOrder()).orElse(Instant.MAX);
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

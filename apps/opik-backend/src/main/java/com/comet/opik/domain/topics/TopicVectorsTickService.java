package com.comet.opik.domain.topics;

import com.comet.opik.domain.ailabels.AiLabelTask;
import com.comet.opik.domain.ailabels.AiLabelTaskRegistry;
import com.comet.opik.domain.topics.TopicVectorsDAO.Candidate;
import com.comet.opik.infrastructure.AiLabelsConfig;
import com.comet.opik.infrastructure.TopicsConfig;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.Exceptions;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.Collectors;

/**
 * One topic vectors tick: for every workspace topics and ai-labels are enabled for, find the traces whose latest
 * {@code trace_summary} status is done and that have no vector yet, embed their summaries and store the unit-length
 * vectors in {@code topic_vectors}. Runs on the leader only.
 *
 * <p>Per workspace, one read of the done statuses written in {@code window}, up to {@code maxTracesPerTick}, oldest
 * first, with their summaries; then {@link TopicEmbedder} embeds them chunk by chunk and each chunk's vectors are
 * written before the next call.
 *
 * <p>ClickHouse alone says which traces have no vector: every read is the done statuses less the keys already in
 * {@code topic_vectors}, so a repeated, overlapping or late tick changes nothing, and a leader change loses nothing.
 * Work per tick is bounded per workspace by {@code maxTracesPerTick} and in total by the tick budget; what is left is
 * found again by the next tick. Workspaces are visited from an offset that moves every tick, so none is always last.
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
     * How long the leader lock must last: the tick budget, plus what can run past its end: an embed call started with
     * {@code embedTimeout} left, a window read started just before it, and {@code leaseSlack} for the insert after the
     * last call. The same shape as {@code AiLabelsTickService.leaderLease()}.
     */
    public Duration leaderLease() {
        var vectors = config.getVectors();
        return vectors.getTickBudget().toJavaDuration()
                .plus(vectors.getEmbedTimeout().toJavaDuration())
                .plus(vectors.getFind().getMaxExecutionTime().toJavaDuration())
                .plus(vectors.getLeaseSlack().toJavaDuration());
    }

    /**
     * @throws IllegalStateException when ai-labels has no {@code trace_summary} task: no tick can run, so the job counts
     *         the tick as failed rather than as one that ran and found nothing
     */
    public TickResult tick(@NonNull Instant start) {
        var vectors = config.getVectors();
        var task = registry.find(TopicEmbedding.SUMMARY_TASK).orElseThrow(() -> new IllegalStateException(
                "ai-labels has no task '%s'".formatted(TopicEmbedding.SUMMARY_TASK)));
        var lowerBound = lowerBound(earliestAppliesFrom(task), start, vectors.getWindow().toJavaDuration());
        if (lowerBound.isAfter(start)) {
            return TickResult.EMPTY;
        }
        var find = TopicVectorsFind.builder()
                .task(TopicEmbedding.SUMMARY_TASK)
                .lowerBound(lowerBound)
                .upperBound(start)
                .createdAfter(createdAfter(lowerBound, aiLabelsConfig.getHorizon().toJavaDuration(),
                        vectors.getFind().getCreatedAtSlack().toJavaDuration()))
                .limit(vectors.getMaxTracesPerTick());
        var budget = TickBudget.of(start, vectors.getTickBudget().toJavaDuration(), Clock.systemUTC());
        var workspaceIds = rotated(workspaces.enabledWorkspaceIds(), tickSequence(start));

        var counts = new TickCounts();
        var found = metrics.timed("find", () -> find(workspaceIds, find, budget));
        metrics.timed("embed", () -> {
            embedAndWrite(found, budget, counts);
            return null;
        });
        return counts.result();
    }

    /** The window read of each workspace, in visiting order, while the budget has time for one. */
    private List<Candidate> find(List<String> workspaceIds, TopicVectorsFind.TopicVectorsFindBuilder find,
            TickBudget budget) {
        var maxExecutionTime = config.getVectors().getFind().getMaxExecutionTime().toJavaDuration();
        var oldest = new LinkedHashMap<String, Instant>();
        var found = new ArrayList<Candidate>();
        for (int i = 0; i < workspaceIds.size(); i++) {
            var workspaceId = workspaceIds.get(i);
            if (!budget.hasTimeForRead()) {
                var notRead = workspaceIds.subList(i, workspaceIds.size());
                metrics.workspacesNotRead(notRead);
                log.warn("Topic vectors window reads ran out of the tick budget, workspaces not read '{}' of '{}'",
                        notRead.size(), workspaceIds.size());
                break;
            }
            try {
                var read = dao.findUnvectored(find.workspaceId(workspaceId)
                        .maxExecutionSeconds(budget.readSeconds(maxExecutionTime))
                        .build()).collectList().block();
                oldest.put(workspaceId, read.stream().map(Candidate::statusUpdatedAt)
                        .min(Comparator.naturalOrder()).orElse(Instant.MAX));
                found.addAll(read);
            } catch (RuntimeException exception) {
                rethrowIfInterrupted(exception);
                metrics.readFailure("find", workspaceId, exception);
                log.warn("Topic vectors window read failed, workspace '{}'; reading it again next tick", workspaceId,
                        exception);
            }
        }
        metrics.oldest(oldest);
        return found;
    }

    /** Per workspace, oldest first: embed in chunks and write each chunk's vectors before the next call. */
    private void embedAndWrite(List<Candidate> candidates, TickBudget budget, TickCounts counts) {
        var byWorkspace = candidates.stream().collect(Collectors.groupingBy(Candidate::workspaceId,
                LinkedHashMap::new, Collectors.toList()));
        byWorkspace.forEach((workspaceId, group) -> {
            for (var outcome : embedder.embed(workspaceId, group, budget)) {
                outcome.skipped().forEach((reason, traces) -> skipped(workspaceId, reason, traces, counts));
                var vectors = outcome.vectors();
                if (vectors.isEmpty()) {
                    continue;
                }
                try {
                    dao.insert(vectors).block();
                } catch (RuntimeException exception) {
                    rethrowIfInterrupted(exception);
                    skipped(workspaceId, SkipReason.INSERT_FAILED, vectors.size(), counts);
                    log.warn("Topic vectors insert failed, workspace '{}', rows '{}'; embedding them again next tick",
                            workspaceId, vectors.size(), exception);
                    continue;
                }
                counts.written(vectors.size());
                metrics.written(workspaceId, vectors.size());
            }
        });
    }

    private void skipped(String workspaceId, SkipReason reason, int traces, TickCounts counts) {
        counts.skipped(reason, traces);
        metrics.skipped(workspaceId, reason, traces);
    }

    /**
     * Shutdown disposes the tick, which interrupts its thread; {@code block()} then throws with the
     * {@link InterruptedException} as the cause. The tick must stop there instead of going on to the next workspace.
     */
    private static void rethrowIfInterrupted(RuntimeException exception) {
        if (Exceptions.unwrap(exception) instanceof InterruptedException || Thread.currentThread().isInterrupted()) {
            Thread.currentThread().interrupt();
            throw exception;
        }
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

    /** The tick's number since the epoch, in tick intervals: the same on every pod for the same minute. */
    private long tickSequence(Instant start) {
        return start.toEpochMilli() / Math.max(1, config.getVectors().getTickInterval().toMilliseconds());
    }

    static <T> List<T> rotated(@NonNull List<T> items, long sequence) {
        if (items.isEmpty()) {
            return items;
        }
        int offset = (int) Math.floorMod(sequence, (long) items.size());
        var rotated = new ArrayList<T>(items.size());
        rotated.addAll(items.subList(offset, items.size()));
        rotated.addAll(items.subList(0, offset));
        return rotated;
    }
}

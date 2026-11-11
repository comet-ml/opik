package com.comet.opik.domain.topics;

import jakarta.annotation.Nullable;
import lombok.Builder;
import lombok.NonNull;

import java.time.Instant;
import java.util.Comparator;
import java.util.UUID;

/**
 * One row of {@code topic_vector_backfills}: what the topic vectors tick last saw of a project's backfill. Written only
 * by the leader's tick. ClickHouse stays the only source of which traces have no vector.
 *
 * @param startedAt when this backfill began: the row was created, or reset because the tick did not run for the
 *        workspace for 2 x {@code topics.vectors.longWindowInterval} (it was disabled, or topics were off)
 * @param checkedAt the last time the tick discovered the workspace's projects, which it does about once per
 *        {@code longWindowInterval}, or created the row. Older than twice that: the tick has stopped for the workspace,
 *        and {@code drainedAt} says nothing about the traces since
 * @param drainedAt the start of the tick that drained the project since {@code startedAt}, null until one does.
 *        Drained: the tick read the project's whole backfill window, found fewer than {@code maxTracesPerTick} traces,
 *        and left none of them for want of time, a read, a back-off or an insert. So every trace of the project with a
 *        done status written in the window up to that instant, and a usable summary, had a vector when the tick ended,
 *        but for those the embed service refuses or answers with an invalid vector
 * @param lastDoneAt the project's latest done status, as last discovered, or as the tick that created the row saw it
 * @param tracesTotal about how many traces of the project can get a vector: its done status rows of traces created
 *        in the backfill window, as last discovered. Too high by the traces whose summary is blank or missing and by
 *        those the embed service refuses, which never get a vector, and by a relabelled trace's older done rows until
 *        ClickHouse merges them. 0 until first discovered
 * @param tracesDone about how many of them have a vector: the project's vector rows, not deleted, of traces created
 *        in the backfill window, as last discovered, plus the vectors the backfill wrote since. Never above
 *        {@code tracesTotal}. Not kept up once the project is drained
 */
@Builder(toBuilder = true)
public record TopicVectorBackfill(
        @NonNull String workspaceId,
        @NonNull UUID projectId,
        @NonNull Instant startedAt,
        @NonNull Instant checkedAt,
        @Nullable Instant drainedAt,
        @NonNull Instant lastDoneAt,
        long tracesTotal,
        long tracesDone) {

    /** The traces still to embed, as the counts have them: approximate, never negative. */
    public long remaining() {
        return Math.max(0, tracesTotal - tracesDone);
    }

    /**
     * The order the backfill takes a workspace's projects in; the only place that decides it. First the projects that
     * fit in one tick, those with at most {@code maxTracesPerTick} traces {@link #remaining()}: the fewest remaining
     * first, then the most recently active. Then the rest, the most recently active first. So many small projects are
     * ready before a large one is. The counts are approximate, so a project of the first group may still need a second
     * tick.
     */
    public static Comparator<TopicVectorBackfill> backfillOrder(int maxTracesPerTick) {
        Comparator<TopicVectorBackfill> recentFirst = Comparator.comparing(TopicVectorBackfill::lastDoneAt).reversed();
        return Comparator
                .comparing((TopicVectorBackfill backfill) -> backfill.remaining() > maxTracesPerTick)
                .thenComparingLong(backfill -> backfill.remaining() > maxTracesPerTick ? 0 : backfill.remaining())
                .thenComparing(recentFirst)
                .thenComparing(TopicVectorBackfill::projectId);
    }

    /** What one tick's backfill did for a project: drained it, or not ({@code drainedAt} null), and wrote vectors. */
    @Builder(toBuilder = true)
    public record Progress(
            @NonNull String workspaceId,
            @NonNull UUID projectId,
            @Nullable Instant drainedAt,
            long written) {
    }
}

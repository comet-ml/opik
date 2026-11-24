package com.comet.opik.domain.topics;

import jakarta.annotation.Nullable;
import lombok.Builder;
import lombok.NonNull;

import java.time.Instant;
import java.util.UUID;

/**
 * A project's backfill columns.
 *
 * @param backfillStartedAt when this backfill began: the tick first saw the project, or reset the backfill because
 *        it did not run for the workspace for 2 x {@code topics.vectors.longWindowInterval}
 * @param backfillDrainedAt the start of the tick that drained the project since {@code backfillStartedAt}, null
 *        until one does. Drained: the tick read the project's whole backfill window, found fewer than
 *        {@code maxTracesPerTick} traces, and left none of them for want of time, a read, a back-off or an insert.
 *        So every trace with a done status in the window up to that instant, and a usable summary, had a vector
 *        when the tick ended, but for those the embed service refuses or answers with an invalid vector
 * @param backfillLastDoneAt the project's latest done status, as last discovered
 * @param backfillTracesTotal about how many traces of the project can get a vector: its traces created in the
 *        backfill window with a done status, as last discovered. Too high by the traces whose summary is blank or
 *        missing and by those the embed service refuses
 * @param backfillTracesDone about how many of them have a vector, as last discovered, plus the vectors the
 *        backfill wrote since. Never above {@code backfillTracesTotal}. Not kept up once the project is drained
 */
@Builder(toBuilder = true)
public record TopicBackfill(
        @NonNull String workspaceId,
        @NonNull UUID projectId,
        @NonNull Instant backfillStartedAt,
        @Nullable Instant backfillDrainedAt,
        @NonNull Instant backfillLastDoneAt,
        long backfillTracesTotal,
        long backfillTracesDone) {

    /** The traces still to embed, as the counts have them: approximate, never negative. */
    public long remaining() {
        return Math.max(0, backfillTracesTotal - backfillTracesDone);
    }
}

package com.comet.opik.infrastructure;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Builder;

/**
 * Toggles for the schema state of the analytics database (non-nullable trace-column migration).
 *
 * <p>{@code traceColumnsNonNullable}: while the {@code traces} table still has {@code Nullable(...)} columns (default,
 * {@code false}) trace writes bind {@code null} for an absent {@code end_time}/{@code ttft}.
 * Once replaced those columns with sentinel-defaulted non-nullable columns, set this {@code true} so writes bind the
 * sentinels ({@code end_time}→epoch, {@code ttft}→{@code NaN}) instead — a {@code null} bind would be rejected by a
 * non-nullable column. The flag gates reads too (sentinel→{@code null}), so while it is {@code false} a legitimate
 * epoch end time round-trips unchanged rather than being read as {@code null}. Flip this in lockstep with the EXCHANGE
 * step of the cutover.</p>
 *
 * <p>{@code spanColumnsNonNullable}: the {@code spans} sibling of {@code traceColumnsNonNullable}, gating the same
 * sentinel wiring for {@code spans.end_time}→epoch and {@code spans.duration}/{@code spans.ttft}→{@code NaN}. Default
 * {@code false} while the {@code spans} table still has {@code Nullable(...)} columns; set {@code true} in lockstep with
 * the Slice 3 EXCHANGE once those columns are replaced with sentinel-defaulted non-nullable columns. Independent of the
 * trace flag so the two cutovers can flip separately.</p>
 *
 * <p>{@code traceDeletionEventsCaptureEnabled}: when {@code true}, trace deletes also record the deleted ids in the
 * {@code deletion_events_local} bridge so they survive the table copy. Left {@code false} at deploy time and turned on
 * once the trace backfill begins, so capture spans exactly the backfill-to-cutover window.</p>
 *
 * <p>{@code spanDeletionEventsCaptureEnabled}: the {@code spans} sibling of {@code traceDeletionEventsCaptureEnabled}.
 * Spans have no standalone delete, so this captures the span ids removed by the trace-delete cascade
 * ({@code SpanService.deleteByTraceIds}) into the bridge with {@code source_table = spans}, so they survive the
 * {@code spans} table copy. Left {@code false} at deploy time and turned on once the span backfill begins, independently
 * of the trace flag.</p>
 *
 * <p>{@code deletionEventsInsertBatchSize}: rows per {@code INSERT} into the bridge (shared by trace and span capture). A single delete batch can carry
 * far more ids than the ClickHouse driver binds reliably in one statement (5 columns per row), so the insert is split
 * into chunks of this size. Bounded to a positive value so a misconfiguration fails startup rather than silently
 * disabling capture, and to a sensible ceiling that keeps the per-statement bind count in the safe range.</p>
 *
 * <p>{@code tracesDistributedWrapEnabled}: the final sharding-readiness step of the traces cutover wraps {@code traces}
 * as a {@code Distributed} table over the {@code traces_local} shard. A {@code Distributed} table supports
 * {@code SELECT} and {@code INSERT} but <b>not</b> mutations ({@code DELETE FROM <distributed>} → code 36;
 * {@code ALTER ... DELETE} → code 48), so once the wrap is live every mutation path must target the local shard.
 * Left {@code false} at deploy time (and while {@code traces} is still a {@code MergeTree}, where deletes work
 * directly); set {@code true} in lockstep with applying the {@code Distributed} wrap
 * ({@code exchange_and_wrap.sh --with-wrap} / {@code --wrap-only}). While {@code true}, {@code TraceDAO} routes its
 * delete/retention mutations to {@code traces_local} while reads and inserts continue through the Distributed
 * {@code traces}. <b>General rule, by kind of change:</b> row mutations ({@code DELETE}) and
 * {@code MATERIALIZE COLUMN} / {@code ADD INDEX} / {@code MODIFY TTL} target {@code traces_local} only — the
 * {@code Distributed} {@code traces} rejects them (code 36/48), so a slip fails loudly; {@code ADD}/{@code DROP}/
 * {@code MODIFY COLUMN} must be applied to <b>both</b> {@code traces_local} and the {@code Distributed} {@code traces}
 * (the wrapper accepts them as metadata-only, and targeting only {@code traces_local} leaves the wrapper without the
 * column, so reads fail with code 47).</p>
 *
 * <p>{@code tracesWeeklyPartitionPruningEnabled}: enables partition-aware <b>pruning</b> of trace deletes — a trace
 * {@code DELETE} bounds itself to the weekly partitions its own ids resolve to instead of being planned against every
 * part of the table (OPIK-6901). It does <b>not</b> create or activate any partitioning; installing the partitioned
 * schema is the EXCHANGE step of the cutover. Turning it on therefore <b>asserts</b> a schema fact rather than causing
 * one: that the live mutation target already is the weekly partitioned successor — {@code id_at} as
 * {@code DateTime64(0, 'UTC')} under
 * {@code PARTITION BY toYYYYMMDD(toDate32(id_at) - toIntervalDay(toDayOfWeek(id_at, 1)))}. Purely an optimisation:
 * {@code false} keeps the unbounded mutation, which is always correct and merely slower, and only {@code true} asserts
 * anything about the schema.</p>
 *
 * <p>It is deliberately a third flag rather than a reuse of the two above, because the partitioning appears at the
 * <b>EXCHANGE</b> and neither of them marks that moment. {@code traceColumnsNonNullable} must be rolled out
 * <b>before</b> the EXCHANGE (a rolling restart cannot be atomic with a metadata swap), and
 * {@code tracesDistributedWrapEnabled} flips at the wrap, a separate step that may be deferred long after it — so one
 * flag would be true too early and the other true too late. Emitting the predicate too early is the harmful direction:
 * legacy {@code traces} has no {@code PARTITION BY} at all and declares {@code id_at} as a 32-bit {@code DateTime} that
 * overflows past 2106, so a far-future id is stored under a wrapped timestamp the derived partition cannot match and
 * the delete would silently affect zero rows. Left {@code false} at deploy time; set {@code true} once the EXCHANGE is
 * confirmed on the target, and back to {@code false} <b>before</b> a rollback promotes the original {@code traces}.</p>
 */
@Builder(toBuilder = true)
public record DatabaseAnalyticsDataModelConfig(
        boolean traceColumnsNonNullable,
        boolean spanColumnsNonNullable,
        boolean traceDeletionEventsCaptureEnabled,
        boolean spanDeletionEventsCaptureEnabled,
        @Min(1) @Max(2_000) int deletionEventsInsertBatchSize,
        boolean tracesDistributedWrapEnabled,
        boolean tracesWeeklyPartitionPruningEnabled) {
}

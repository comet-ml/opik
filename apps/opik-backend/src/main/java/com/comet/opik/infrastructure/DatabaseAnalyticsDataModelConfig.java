package com.comet.opik.infrastructure;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Builder;

/**
 * Toggles for the state and capabilities of the analytics database (non-nullable trace-column migration,
 * deletion-event capture, and query constructs whose availability depends on the ClickHouse version).
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
 * <p>{@code materializedCteEnabled}: when {@code true}, queries may declare a CTE {@code AS MATERIALIZED} so it is
 * evaluated once instead of once per reference, emitting the {@code enable_materialized_cte} setting alongside the
 * keyword — both are required, since with the setting absent the keyword parses and is silently ignored. Requires
 * ClickHouse 26.3+: on 25.8 the keyword is a {@code SYNTAX_ERROR} and the setting an {@code UNKNOWN_SETTING}, either of
 * which fails the query outright rather than degrading, so an environment pointed at an older external ClickHouse must
 * leave this {@code false}. The bundled ClickHouse is 26.3.</p>
 *
 * <p>{@code deletionEventsInsertBatchSize}: rows per {@code INSERT} into the bridge (shared by trace and span capture). A single delete batch can carry
 * far more ids than the ClickHouse driver binds reliably in one statement (5 columns per row), so the insert is split
 * into chunks of this size. Bounded to a positive value so a misconfiguration fails startup rather than silently
 * disabling capture, and to a sensible ceiling that keeps the per-statement bind count in the safe range.</p>
 */
@Builder(toBuilder = true)
public record DatabaseAnalyticsDataModelConfig(
        boolean traceColumnsNonNullable,
        boolean spanColumnsNonNullable,
        boolean traceDeletionEventsCaptureEnabled,
        boolean spanDeletionEventsCaptureEnabled,
        boolean materializedCteEnabled,
        @Min(1) @Max(2_000) int deletionEventsInsertBatchSize) {
}

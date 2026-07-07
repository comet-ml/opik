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
 * step of the cutover. A sibling {@code spanColumnsNonNullable} follows later.</p>
 *
 * <p>{@code traceDeletionEventsCaptureEnabled}: when {@code true}, trace deletes also record the deleted ids in the
 * {@code deletion_events_local} bridge so they survive the table copy. Left {@code false} at deploy time and turned on
 * once the trace backfill begins, so capture spans exactly the backfill-to-cutover window. A sibling
 * {@code spanDeletionEventsCaptureEnabled} follows when spans migrate.</p>
 *
 * <p>{@code deletionEventsInsertBatchSize}: rows per {@code INSERT} into the bridge. A single delete batch can carry
 * far more ids than the ClickHouse driver binds reliably in one statement (5 columns per row), so the insert is split
 * into chunks of this size. Bounded to a positive value so a misconfiguration fails startup rather than silently
 * disabling capture, and to a sensible ceiling that keeps the per-statement bind count in the safe range.</p>
 */
@Builder(toBuilder = true)
public record DatabaseAnalyticsDataModelConfig(
        boolean traceColumnsNonNullable,
        boolean traceDeletionEventsCaptureEnabled,
        @Min(1) @Max(2_000) int deletionEventsInsertBatchSize) {
}

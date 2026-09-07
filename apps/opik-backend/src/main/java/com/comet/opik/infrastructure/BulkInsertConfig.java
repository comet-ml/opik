package com.comet.opik.infrastructure;

import lombok.Builder;

/**
 * Which client the bulk write paths use.
 *
 * <p>{@code v2ClientEnabled} switches {@code ExperimentItemDAO#insert}, {@code TraceDAO#batchInsert} and
 * {@code SpanDAO#batchInsert} between two implementations of the same insert:
 *
 * <ul>
 *   <li>{@code false} (default) — the R2DBC bulk path, which renders one placeholder per column per row
 *       and binds each by name. The driver resolves every bind with a linear scan over the statement's
 *       parameter names, so a 1000-row span batch carries ~27k names (27 row-indexed
 *       binds plus the shared workspace bind) and binding is O(n²).</li>
 *   <li>{@code true} — rows serialized to {@code JSONEachRow} and streamed through the ClickHouse Java
 *       client v2: one HTTP body, compressed once, parsed server-side. No parameter binding at all.</li>
 * </ul>
 *
 * <p>Both paths write identical cells, so this is safe to flip in either direction on a running install
 * and needs no coordination with a migration — unlike {@code DatabaseAnalyticsDataModelConfig}, whose
 * flags must match the physical schema. The sentinel behaviour the v2 path emits is still governed by
 * that config, not by this one.
 *
 * <p>Defaults to {@code false} so an upgrade does not silently change the write path. Keeping both in
 * the image is deliberate for A/B measurement: an experiment then varies one setting rather than the
 * build, which removes image-build differences as a confounder.
 */
@Builder(toBuilder = true)
public record BulkInsertConfig(boolean v2ClientEnabled) {
}

package com.comet.opik.infrastructure;

import lombok.Builder;

/**
 * Which client the bulk write paths use.
 *
 * <p>{@code v2ClientEnabled} switches a bulk row-append between two implementations of the same insert.
 * It currently governs {@code FeedbackScoreDAO}'s batch insert; the other bulk paths follow separately.
 *
 * <ul>
 *   <li>{@code false} (default) — the R2DBC bulk path, which renders one placeholder per column per row
 *       and binds each by name. The driver resolves every bind with a linear scan over the statement's
 *       parameter names, so a batch of n scores carries 10n row-indexed names on the authored table and
 *       binding is O(n²). Feedback scores are not capped the way the other bulk endpoints are:
 *       {@code ExperimentItemBulkIngestionService} accumulates up to 100 scores per record over up to
 *       1000 records into a single call.</li>
 *   <li>{@code true} — rows serialized to {@code JSONEachRow} and streamed through the ClickHouse Java
 *       client v2: one HTTP body, compressed once, parsed server-side. No parameter binding at all.</li>
 * </ul>
 *
 * <p>Both paths write identical cells, so this is safe to flip in either direction on a running install
 * and needs no coordination with a migration — unlike {@code DatabaseAnalyticsDataModelConfig}, whose
 * flags must match the physical schema.
 *
 * <p>Defaults to {@code false} so an upgrade does not silently change the write path. Keeping both in
 * the image is deliberate for A/B measurement: an experiment then varies one setting rather than the
 * build, which removes image-build differences as a confounder.
 */
@Builder(toBuilder = true)
public record BulkInsertConfig(boolean v2ClientEnabled) {
}

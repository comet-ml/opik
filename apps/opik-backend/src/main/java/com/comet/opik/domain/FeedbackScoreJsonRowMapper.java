package com.comet.opik.domain;

import com.comet.opik.api.FeedbackScoreItem;
import com.comet.opik.utils.JsonUtils;
import com.comet.opik.utils.SentinelTranslation;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.Nullable;
import lombok.NonNull;
import lombok.experimental.UtilityClass;
import org.apache.commons.lang3.StringUtils;

/**
 * Builds the {@code JSONEachRow} row for a feedback score, for the write path behind
 * {@code bulkInsert.v2ClientEnabled}.
 *
 * <p>The counterpart of {@code FeedbackScoreDAOImpl#bindParameters}: the two must produce identical
 * cells, since the toggle is meant to be safe to flip either way on a running install. Both normalize
 * absent text with {@link StringUtils#stripToEmpty(String)} for that reason.
 */
@UtilityClass
class FeedbackScoreJsonRowMapper {

    /**
     * The row as {@code JSONEachRow}, rather than 8 named parameters per row — 10 for the authored
     * table.
     *
     * <p>Which table it is destined for is the caller's choice, but the two are not independent:
     * {@code feedback_scores} has no {@code author}/{@code source_queue_id} columns, so those two fields
     * are emitted only when the author is set, matching {@code <if(author)>} on the R2DBC template.
     *
     * @param normalizedAuthor already trimmed by the caller, since it is invariant across the batch
     *                         while this runs per row. {@code null} — not {@code ""} — means the
     *                         unauthored table, and drops both columns.
     */
    ObjectNode toJsonRow(@NonNull FeedbackScoreItem score, @NonNull EntityType entityType,
            @NonNull String userName, @NonNull String workspaceId, @Nullable String normalizedAuthor) {

        var node = JsonUtils.createObjectNode();

        node.put("entity_type", entityType.getType());
        node.put("entity_id", score.id().toString());
        node.put("project_id", score.projectId().toString());
        node.put("workspace_id", workspaceId);
        node.put("name", score.name());
        node.put("category_name", StringUtils.stripToEmpty(score.categoryName()));
        // toPlainString, not toString: the latter switches to scientific notation below 1e-6 and for a
        // negative scale, so an ordinary 0.0000001 would go out as "1E-7". Verified against ClickHouse
        // that both notations parse to the same Decimal(18, 9) cell, through JSONEachRow and through
        // FORMAT Values, so this differs from the R2DBC bind's toString() on the wire only — the stored
        // value, and anything already stored, is unaffected.
        node.put("value", score.value().toPlainString());
        node.put("reason", StringUtils.stripToEmpty(score.reason()));
        node.put("source", score.source().getValue());

        if (normalizedAuthor != null) {
            node.put("author", normalizedAuthor);
            // FixedString(36) with no DEFAULT: "" for an absent queue id, which the column zero-pads —
            // the same cell the R2DBC bind writes.
            node.put("source_queue_id", SentinelTranslation.nullToEmptyUuid(score.sourceQueueId()));
        }

        node.put("created_by", userName);
        node.put("last_updated_by", userName);

        // created_at and last_updated_at stay absent so their column DEFAULTs stamp them server-side,
        // exactly as the R2DBC column list does — last_updated_at is the ReplacingMergeTree version, so
        // a zero there would make every later score for the same key lose to the original row. This is
        // why the insert sets input_format_defaults_for_omitted_fields.
        return node;
    }
}

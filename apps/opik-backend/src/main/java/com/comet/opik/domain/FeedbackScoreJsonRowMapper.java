package com.comet.opik.domain;

import com.comet.opik.api.FeedbackScoreItem;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.Nullable;
import lombok.NonNull;
import org.apache.commons.lang3.StringUtils;

import java.util.Optional;
import java.util.UUID;

/**
 * Builds the {@code JSONEachRow} row for a feedback score, for the write path behind
 * {@code bulkInsert.v2ClientEnabled}.
 *
 * <p>The counterpart of {@code FeedbackScoreDAOImpl#bindParameters}: the two must produce identical
 * cells, since the toggle is meant to be safe to flip either way on a running install. Both normalize
 * absent text with {@link StringUtils#trimToEmpty(String)} for that reason.
 */
class FeedbackScoreJsonRowMapper {

    private FeedbackScoreJsonRowMapper() {
    }

    /**
     * The row as {@code JSONEachRow}, rather than 8 named parameters per row — 10 for the authored
     * table.
     *
     * <p>Which table it is destined for is the caller's choice, but the two are not independent:
     * {@code feedback_scores} has no {@code author}/{@code source_queue_id} columns, so those two fields
     * are emitted only when {@code author} is set, matching {@code <if(author)>} on the R2DBC template.
     */
    static ObjectNode toJsonRow(@NonNull FeedbackScoreItem score, @NonNull EntityType entityType,
            @Nullable String author, @NonNull String userName, @NonNull String workspaceId) {

        var node = JsonUtils.createObjectNode();

        node.put("entity_type", entityType.getType());
        node.put("entity_id", score.id().toString());
        node.put("project_id", score.projectId().toString());
        node.put("workspace_id", workspaceId);
        node.put("name", score.name());
        node.put("category_name", StringUtils.trimToEmpty(score.categoryName()));
        // Decimal(18, 9) written as a quoted plain string, as ExperimentAggregatesDAOImpl does for
        // total_estimated_cost — no exponent notation, and no float round-tripping.
        node.put("value", score.value().toPlainString());
        node.put("reason", StringUtils.trimToEmpty(score.reason()));
        node.put("source", score.source().getValue());

        if (author != null) {
            node.put("author", StringUtils.trimToEmpty(author));
            // FixedString(36) with no DEFAULT: "" for an absent queue id, which the column zero-pads —
            // the same cell the R2DBC bind writes.
            node.put("source_queue_id",
                    Optional.ofNullable(score.sourceQueueId()).map(UUID::toString).orElse(""));
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

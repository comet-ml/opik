package com.comet.opik.domain;

import com.comet.opik.api.Span;
import com.comet.opik.infrastructure.db.JsonRowValues;
import com.comet.opik.utils.ClickHouseDateTimeFormat;
import com.comet.opik.utils.JsonUtils;
import com.comet.opik.utils.TruncationUtils;
import com.comet.opik.utils.UsageUtils;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.NonNull;
import lombok.experimental.UtilityClass;
import org.apache.commons.lang3.StringUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

import static com.comet.opik.utils.SentinelTranslation.nullToEpoch;
import static com.comet.opik.utils.SentinelTranslation.nullToNaN;

/**
 * Builds the {@code JSONEachRow} row for a span, for the write path behind
 * {@code bulkInsert.v2ClientEnabled}.
 *
 * <p>The counterpart of {@code BULK_INSERT}'s parameter binding in {@code SpanDAO}: the two must produce
 * identical cells, since the toggle is meant to be safe to flip either way on a running install. At 28
 * columns this is the widest of the trace/span pair, so it is where the driver's per-name linear scan
 * costs most.
 *
 * <p>Three places where matching the binder matters more than looking tidy:
 * <ul>
 * <li>{@code metadata} uses {@code JsonNode.toString()}, <b>not</b> {@code TruncationUtils.toJsonString}
 * as {@code input} / {@code output} do. That asymmetry is in the binder; reproducing it is the point.</li>
 * <li>{@code total_estimated_cost_version} is stamped only when the cost was <em>computed here</em> and
 * came out positive — a caller-supplied cost, or a computed zero, leaves it empty.</li>
 * <li>{@code end_time} and {@code ttft} are mid-migration: {@code Nullable} until
 * {@code spanColumnsNonNullable} flips, epoch / NaN sentinels after. Both states are live in different
 * environments, so this mirrors {@code bindEpochSentinel} / {@code bindNanSentinel} branch for branch.</li>
 * </ul>
 *
 * <p>Config reaches this mapper as arguments rather than through the DAO, so a row mapper never depends
 * on the DAO that calls it.
 */
@UtilityClass
class SpanJsonRowMapper {

    /**
     * @param cost               the resolved cost — supplied by the caller because deriving it needs the
     *                           DAO's cost model.
     * @param costVersion        the cost-model version to stamp, or {@code ""}. Resolved by the caller
     *                           because the rule and the version constant both belong to the DAO: it is
     *                           stamped only when the cost was computed there rather than supplied on the
     *                           span, and came out positive.
     * @param nonNullableColumns {@code spanColumnsNonNullable} — whether {@code end_time} / {@code ttft}
     *                           have been migrated off {@code Nullable} to their sentinels.
     * @param truncationSize     {@code responseFormatting.truncationSize}. Non-positive leaves the column
     *                           out so its DDL default applies, matching the binder's {@code bindNull}.
     * @param nowForBatch        fallback for an absent {@code last_updated_at}, resolved once per batch.
     */
    ObjectNode toJsonRow(@NonNull Span span, @NonNull String userName, @NonNull String workspaceId,
            @NonNull Instant nowForBatch, @NonNull BigDecimal cost, @NonNull String costVersion,
            boolean nonNullableColumns, int truncationSize) {

        // Computed once: each is also the input to its *_slim counterpart below.
        String inputValue = TruncationUtils.toJsonString(span.input());
        String outputValue = TruncationUtils.toJsonString(span.output());

        var node = JsonUtils.createObjectNode();

        node.put("id", span.id().toString());
        node.put("project_id", span.projectId().toString());
        node.put("workspace_id", workspaceId);
        node.put("trace_id", span.traceId().toString());
        // String DEFAULT '', not Nullable: an absent parent is "" rather than a JSON null.
        node.put("parent_span_id", span.parentSpanId() != null ? span.parentSpanId().toString() : "");
        node.put("name", StringUtils.defaultIfBlank(span.name(), ""));
        node.put("type", Objects.toString(span.type(), SpanType.UNKNOWN_VALUE));
        node.put("start_time", ClickHouseDateTimeFormat.formatNanos(span.startTime()));

        if (nonNullableColumns) {
            node.put("end_time", ClickHouseDateTimeFormat.formatNanos(nullToEpoch(span.endTime())));
        } else if (span.endTime() != null) {
            node.put("end_time", ClickHouseDateTimeFormat.formatNanos(span.endTime()));
        } else {
            node.putNull("end_time");
        }

        node.put("input", inputValue);
        node.put("output", outputValue);
        // toString(), not toJsonString() -- see the class javadoc. Matches the binder.
        node.put("metadata", span.metadata() != null ? span.metadata().toString() : "");
        node.put("model", StringUtils.defaultIfBlank(span.model(), ""));
        node.put("provider", StringUtils.defaultIfBlank(span.provider(), ""));

        // Decimal128(12) as a quoted plain string: toPlainString avoids the exponent notation
        // BigDecimal.toString() produces for small values, which the Decimal parser rejects.
        node.put("total_estimated_cost", cost.toPlainString());
        node.put("total_estimated_cost_version", costVersion);

        var tags = node.putArray("tags");
        Optional.ofNullable(span.tags()).ifPresent(values -> values.forEach(tags::add));

        // Map(String, Int64): sanitizeUsage is the binder's own filter, reused so the two cannot drift.
        var usage = node.putObject("usage");
        UsageUtils.sanitizeUsage(span.usage()).forEach(usage::put);

        // formatMicros: last_updated_at is DateTime64(6) while start_time / end_time are DateTime64(9).
        node.put("last_updated_at", ClickHouseDateTimeFormat.formatMicros(
                span.lastUpdatedAt() != null ? span.lastUpdatedAt() : nowForBatch));
        node.put("error_info", span.errorInfo() != null ? JsonUtils.readTree(span.errorInfo()).toString() : "");
        node.put("created_by", userName);
        node.put("last_updated_by", userName);
        node.put("input_slim", TruncationUtils.createSlimJsonString(inputValue));
        node.put("output_slim", TruncationUtils.createSlimJsonString(outputValue));

        // Mirrors bindNanSentinel.
        if (nonNullableColumns) {
            JsonRowValues.putDouble(node, "ttft", nullToNaN(span.ttft()));
        } else if (span.ttft() != null) {
            JsonRowValues.putDouble(node, "ttft", span.ttft());
        } else {
            node.putNull("ttft");
        }

        node.put("environment", StringUtils.defaultString(span.environment()));

        // Omitted rather than null when absent so the column DEFAULT applies directly, instead of
        // depending on input_format_null_as_default the way the binder's bindNull does.
        if (truncationSize > 0) {
            node.put("truncation_threshold", truncationSize);
        }

        if (span.source() != null) {
            node.put("source", span.source().getValue());
        }

        return node;
    }
}

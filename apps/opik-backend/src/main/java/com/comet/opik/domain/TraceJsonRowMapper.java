package com.comet.opik.domain;

import com.comet.opik.api.Trace;
import com.comet.opik.api.VisibilityMode;
import com.comet.opik.utils.ClickHouseDateTimeFormat;
import com.comet.opik.utils.JsonUtils;
import com.comet.opik.utils.TruncationUtils;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.NonNull;
import lombok.experimental.UtilityClass;
import org.apache.commons.lang3.StringUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static com.comet.opik.utils.SentinelTranslation.nullToEpoch;
import static com.comet.opik.utils.SentinelTranslation.nullToNaN;

/**
 * Builds the {@code JSONEachRow} row for a trace, for the write path behind
 * {@code bulkInsert.v2ClientEnabled}.
 *
 * <p>The counterpart of {@code BATCH_INSERT}'s parameter binding in {@code TraceDAO}: the two must
 * produce identical cells, since the toggle is meant to be safe to flip either way on a running
 * install. 22 columns, 20 of them bound per row by the binder.
 *
 * <p>The sentinel columns are the reason this one is not a straight field-for-field copy. {@code
 * end_time} and {@code ttft} are mid-migration: {@code Nullable} until {@code traceColumnsNonNullable}
 * flips, an epoch / NaN sentinel after. Both states are live in different environments, so this mirrors
 * {@code bindEpochSentinel} / {@code bindNanSentinel} branch for branch rather than picking one.
 *
 * <p>Config reaches this mapper as arguments rather than through the DAO, so a row mapper never depends
 * on the DAO that calls it.
 */
@UtilityClass
class TraceJsonRowMapper {

    /**
     * @param nonNullableColumns {@code traceColumnsNonNullable} — whether {@code end_time} / {@code ttft}
     *                           have been migrated off {@code Nullable} to their sentinels.
     * @param truncationSize     {@code responseFormatting.truncationSize}. Non-positive means the column
     *                           is left out so its DDL default applies, matching what the binder's
     *                           {@code bindNull} achieves via {@code input_format_null_as_default}.
     * @param nowForBatch        fallback for an absent {@code last_updated_at}, resolved once per batch:
     *                           the helper re-runs this mapper on every insert attempt, and downstream
     *                           {@code MAX(last_updated_at)} aggregations want one timestamp per batch.
     */
    ObjectNode toJsonRow(@NonNull Trace trace, @NonNull String userName, @NonNull String workspaceId,
            @NonNull Instant nowForBatch, boolean nonNullableColumns, int truncationSize) {

        // Computed once: each is also the input to its *_slim counterpart below.
        String inputValue = TruncationUtils.toJsonString(trace.input());
        String outputValue = TruncationUtils.toJsonString(trace.output());

        var node = JsonUtils.createObjectNode();

        node.put("id", trace.id().toString());
        node.put("project_id", trace.projectId().toString());
        node.put("workspace_id", workspaceId);
        node.put("name", StringUtils.defaultIfBlank(trace.name(), ""));
        node.put("start_time", ClickHouseDateTimeFormat.formatNanos(trace.startTime()));

        // Mirrors bindEpochSentinel: the epoch sentinel once the column is non-nullable, an explicit
        // JSON null while it is still Nullable.
        if (nonNullableColumns) {
            node.put("end_time", ClickHouseDateTimeFormat.formatNanos(nullToEpoch(trace.endTime())));
        } else if (trace.endTime() != null) {
            node.put("end_time", ClickHouseDateTimeFormat.formatNanos(trace.endTime()));
        } else {
            node.putNull("end_time");
        }

        node.put("input", inputValue);
        node.put("output", outputValue);
        node.put("metadata", TruncationUtils.toJsonString(trace.metadata()));

        var tags = node.putArray("tags");
        Optional.ofNullable(trace.tags()).ifPresent(values -> values.forEach(tags::add));

        // formatMicros, not formatNanos: last_updated_at is DateTime64(6) here while start_time and
        // end_time are DateTime64(9). The binder makes the same split.
        node.put("last_updated_at", ClickHouseDateTimeFormat.formatMicros(
                trace.lastUpdatedAt() != null ? trace.lastUpdatedAt() : nowForBatch));
        node.put("error_info", trace.errorInfo() != null ? JsonUtils.readTree(trace.errorInfo()).toString() : "");
        node.put("created_by", userName);
        node.put("last_updated_by", userName);
        node.put("thread_id", StringUtils.defaultIfBlank(trace.threadId(), ""));
        node.put("visibility_mode", trace.visibilityMode() != null
                ? trace.visibilityMode().getValue()
                : VisibilityMode.DEFAULT.getValue());
        node.put("input_slim", TruncationUtils.createSlimJsonString(inputValue));
        node.put("output_slim", TruncationUtils.createSlimJsonString(outputValue));

        // Mirrors bindNanSentinel.
        if (nonNullableColumns) {
            putTtft(node, nullToNaN(trace.ttft()));
        } else if (trace.ttft() != null) {
            putTtft(node, trace.ttft());
        } else {
            node.putNull("ttft");
        }

        node.put("environment", StringUtils.defaultString(trace.environment()));

        // truncation_threshold (UInt64 DEFAULT) and source (Enum8 DEFAULT 'unknown') are LEFT OUT when
        // absent rather than written as null. Both columns are non-nullable, so a null only becomes the
        // default via input_format_null_as_default -- which is what the binder's bindNull relies on.
        // Omitting takes the column DEFAULT directly, giving the same cell without that dependency.
        if (truncationSize > 0) {
            node.put("truncation_threshold", truncationSize);
        }

        if (trace.source() != null) {
            node.put("source", trace.source().getValue());
        }

        return node;
    }

    /**
     * Writes {@code ttft} so the stored {@code Float64} is bit-for-bit the double we were given.
     *
     * <p>The obvious {@code node.put(field, double)} writes Jackson's shortest round-tripping decimal.
     * That is exact for a correctly-rounded reader, but ClickHouse's JSON float parse can land 1 ULP
     * away, so the value read back is not the value handed in — a divergence the R2DBC path does not
     * have, since its driver transmits the double in binary. Writing {@link BigDecimal#BigDecimal(double)},
     * the exact binary value expanded in decimal, removes the rounding decision entirely: the only
     * {@code Float64} that text can name is the one we started from.
     *
     * <p>NaN has no {@code BigDecimal}, so the sentinel keeps the plain double form. Jackson quotes
     * non-finite numbers, so it serializes as {@code "NaN"} — which is what the insert's
     * {@code input_format_json_read_numbers_as_strings} exists to accept.
     */
    private void putTtft(ObjectNode node, double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            node.put("ttft", value);
        } else {
            node.put("ttft", new BigDecimal(value));
        }
    }
}

package com.comet.opik.domain;

import com.comet.opik.api.DatasetItem;
import com.comet.opik.infrastructure.db.JsonRowValues;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.NonNull;
import lombok.experimental.UtilityClass;

import java.time.Instant;
import java.util.UUID;

/**
 * Builds the {@code JSONEachRow} row for a dataset item version, for the write path behind
 * {@code bulkInsert.v2ClientEnabled}.
 *
 * <p>The counterpart of {@code BATCH_INSERT_ITEMS}' parameter binding in {@code DatasetItemVersionDAO}:
 * the two must produce identical cells, since the toggle is meant to be safe to flip either way on a
 * running install. This is the widest bulk write in the codebase — 22 columns, 17 of them per-row — so
 * it is where the driver's per-name linear scan costs the most.
 *
 * <p>Column handling that has to match the binder rather than merely look reasonable:
 * <ul>
 * <li>{@code created_at} / {@code last_updated_at} are omitted so their {@code DEFAULT now64(9)} stamps
 * them server-side, as the template's inline {@code now64(9)} does. This is what
 * {@code input_format_defaults_for_omitted_fields} is for. {@code last_updated_at} is also the
 * ReplacingMergeTree version column, so a client clock here would decide which duplicate wins.</li>
 * <li>{@code item_created_at} / {@code item_last_updated_at} have NO default and are required, so they
 * are always written, through the same {@code formatTimestamp} the binder uses — which strips the
 * trailing {@code Z}, hence the insert's {@code date_time_input_format=best_effort}.</li>
 * <li>{@code data_hash}, {@code description_hash}, {@code evaluators_hash},
 * {@code execution_policy_hash} and {@code column_types} are MATERIALIZED and must stay absent.</li>
 * <li>{@code metadata} is written as {@code ""} unconditionally, matching the binder — not carried from
 * the item.</li>
 * </ul>
 */
@UtilityClass
class DatasetItemVersionJsonRowMapper {

    /**
     * The row as {@code JSONEachRow}, rather than 17 named parameters per row plus 5 shared ones.
     *
     * @param nowForBatch the fallback for an item carrying no timestamps. Resolved once for the whole
     *                    batch by the caller rather than per row, because this mapper is re-run on
     *                    every insert attempt: {@code formatTimestamp(null)} would mint a fresh
     *                    {@code Instant.now()} each time, so a retried row would carry different
     *                    timestamp bytes under the same id.
     */
    ObjectNode toJsonRow(@NonNull DatasetItem item, @NonNull UUID datasetId, @NonNull UUID newVersionId,
            @NonNull String workspaceId, @NonNull String userName, @NonNull Instant nowForBatch) {

        var node = JsonUtils.createObjectNode();

        node.put("id", item.id().toString());
        node.put("dataset_item_id", item.datasetItemId().toString());
        node.put("dataset_id", datasetId.toString());
        node.put("dataset_version_id", newVersionId.toString());

        // Map(String, String): each value is the JsonNode's serialized form, exactly as the binder's
        // getOrDefault produces.
        JsonRowValues.putStringMap(node, "data", DatasetItemResultMapper.getOrDefault(item.data()));

        node.put("description", item.description() != null ? item.description() : "");
        node.put("metadata", "");
        // Enum8: ClickHouse takes the enum's name, which is what getValue() returns. The binder's
        // null fallback is 'sdk', not the column's own 'unknown' = 0.
        node.put("source", item.source() != null ? item.source().getValue() : "sdk");
        // String DEFAULT '', not Nullable: an absent one is "" rather than a JSON null.
        node.put("trace_id", DatasetItemResultMapper.getOrDefault(item.traceId()));
        node.put("span_id", DatasetItemResultMapper.getOrDefault(item.spanId()));

        JsonRowValues.putStringArray(node, "tags", item.tags());

        node.put("evaluators", DatasetItemVersionDAOImpl.serializeEvaluators(item.evaluators()));
        node.put("execution_policy", DatasetItemVersionDAOImpl.serializeExecutionPolicy(item.executionPolicy()));

        node.put("item_created_at",
                DatasetItemVersionDAOImpl.formatTimestamp(item.createdAt() != null ? item.createdAt() : nowForBatch));
        node.put("item_last_updated_at", DatasetItemVersionDAOImpl
                .formatTimestamp(item.lastUpdatedAt() != null ? item.lastUpdatedAt() : nowForBatch));
        node.put("item_created_by", item.createdBy() != null ? item.createdBy() : userName);
        node.put("item_last_updated_by", item.lastUpdatedBy() != null ? item.lastUpdatedBy() : userName);

        node.put("created_by", userName);
        node.put("last_updated_by", userName);
        node.put("workspace_id", workspaceId);

        return node;
    }
}

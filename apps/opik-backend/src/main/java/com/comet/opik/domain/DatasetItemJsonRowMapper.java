package com.comet.opik.domain;

import com.comet.opik.api.DatasetItem;
import com.comet.opik.infrastructure.db.JsonRowValues;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.NonNull;
import lombok.experimental.UtilityClass;

import java.util.UUID;

/**
 * Builds the {@code JSONEachRow} row for a dataset item, for the write path behind
 * {@code bulkInsert.v2ClientEnabled}.
 *
 * <p>The counterpart of the {@code INSERT} template's parameter binding in {@code DatasetItemDAO}: the
 * two must produce identical cells, since the toggle is meant to be safe to flip either way on a
 * running install.
 */
@UtilityClass
class DatasetItemJsonRowMapper {

    /**
     * The row as {@code JSONEachRow}, rather than ~10 named parameters per row.
     *
     * <p>Note which columns are <b>not</b> Nullable here: {@code trace_id} and {@code span_id} are
     * {@code String DEFAULT ''}, so an absent one is {@code ""} rather than a JSON null — the value the
     * binder sends via {@code DatasetItemResultMapper.getOrDefault}, reused so the two cannot drift.
     */
    ObjectNode toJsonRow(@NonNull DatasetItem item, @NonNull UUID datasetId, @NonNull String userName,
            @NonNull String workspaceId) {

        var node = JsonUtils.createObjectNode();

        node.put("id", item.id().toString());
        node.put("dataset_id", datasetId.toString());
        // Enum8: ClickHouse takes the enum's name, which is what getValue() returns and what the binder
        // sends.
        node.put("source", item.source().getValue());
        node.put("trace_id", DatasetItemResultMapper.getOrDefault(item.traceId()));
        node.put("span_id", DatasetItemResultMapper.getOrDefault(item.spanId()));

        // Map(String, String): each value is the JsonNode's serialized form, exactly as the binder's
        // getOrDefault produces.
        JsonRowValues.putStringMap(node, "data", DatasetItemResultMapper.getOrDefault(item.data()));
        JsonRowValues.putStringArray(node, "tags", item.tags());

        node.put("workspace_id", workspaceId);
        node.put("created_by", userName);
        node.put("last_updated_by", userName);

        // created_at and last_updated_at stay absent so their column DEFAULTs stamp them server-side,
        // exactly as the R2DBC column list does. This is why the insert sets
        // input_format_defaults_for_omitted_fields.
        return node;
    }
}

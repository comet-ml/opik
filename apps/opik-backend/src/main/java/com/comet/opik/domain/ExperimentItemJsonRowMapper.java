package com.comet.opik.domain;

import com.comet.opik.api.ExperimentItem;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.NonNull;
import lombok.experimental.UtilityClass;

/**
 * Builds the {@code JSONEachRow} row for an experiment item, for the write path behind
 * {@code bulkInsert.v2ClientEnabled}.
 *
 * <p>The counterpart of the {@code INSERT} template's parameter binding in {@code ExperimentItemDAO}:
 * the two must produce identical cells, since the toggle is meant to be safe to flip either way on a
 * running install.
 */
@UtilityClass
class ExperimentItemJsonRowMapper {

    /**
     * The row as {@code JSONEachRow}, rather than ~9 named parameters per row.
     *
     * <p>Duplicate ids are not filtered here, deliberately: the R2DBC path appends a new version for the
     * ReplacingMergeTree to collapse, and this does the same.
     */
    ObjectNode toJsonRow(@NonNull ExperimentItem item, @NonNull String userName, @NonNull String workspaceId) {
        var node = JsonUtils.createObjectNode();

        node.put("id", item.id().toString());
        node.put("experiment_id", item.experimentId().toString());
        node.put("dataset_item_id", item.datasetItemId().toString());
        node.put("trace_id", item.traceId().toString());
        node.put("workspace_id", workspaceId);

        // Nullable(FixedString(36)): an absent project id stays NULL. "" would be a 36-byte FixedString
        // mismatch, and would read back as a project rather than as absent — so this is the one column
        // here that must be an explicit null rather than an omitted field.
        if (item.projectId() != null) {
            node.put("project_id", item.projectId().toString());
        } else {
            node.putNull("project_id");
        }

        node.put("created_by", userName);
        node.put("last_updated_by", userName);
        node.put("execution_policy", ExecutionPolicyMapper.serialize(item.executionPolicy()));

        // created_at and last_updated_at stay absent so their column DEFAULTs stamp them server-side,
        // exactly as the R2DBC column list does — created_at is the stalled-run reaper's liveness signal
        // (OPIK-7459) and last_updated_at is the dedup version. This is why the insert sets
        // input_format_defaults_for_omitted_fields.
        return node;
    }
}

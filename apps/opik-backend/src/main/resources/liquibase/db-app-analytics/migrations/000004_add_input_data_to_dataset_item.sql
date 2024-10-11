--liquibase formatted sql
--changeset thiagohora:add_input_data_to_dataset_item

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.dataset_items
    ADD COLUMN IF NOT EXISTS input_data Map(String, String) DEFAULT map();

INSERT INTO ${ANALYTICS_DB_DATABASE_NAME}.dataset_items
    (
        workspace_id,
        dataset_id,
        source,
        trace_id,
        span_id,
        id,
        input,
        expected_output,
        metadata,
        created_at,
        created_by,
        last_updated_by,
        input_data
    )
SELECT
    workspace_id,
    dataset_id,
    source,
    trace_id,
    span_id,
    id,
    input,
    expected_output,
    metadata,
    created_at,
    created_by,
    last_updated_by,
    multiIf(
        has(input_data, 'input') AND has(input_data, 'expected_output'), input_data,
        has(input_data, 'input') AND not has(input_data, 'expected_output'), mapUpdate(input_data, map('expected_output', concat('{ "type": "json", "value": ', expected_output, ' }'))),
        has(input_data, 'expected_output') AND not has(input_data, 'input'), mapUpdate(input_data, map('input', concat('{ "type": "json", "value": ', input, ' }'))),
        mapUpdate(
            mapUpdate(input_data, map('input', concat('{ "type": "json", "value": ', input, ' }'))),
            map('expected_output', concat('{ "type": "json", "value": ', expected_output, ' }'))
        )
    )
FROM ${ANALYTICS_DB_DATABASE_NAME}.dataset_items
WHERE last_updated_at < now64(9);

--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.dataset_items DROP COLUMN input_data;

--liquibase formatted sql
--changeset thiagohora:add_input_data_to_dataset_item

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
    data
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
    mapUpdate(
        mapUpdate(
            mapUpdate(
                data,
                if(not has(data, 'input'), map('input', input), map())
            ),
            if(not has(data, 'expected_output'), map('expected_output', expected_output), map())
        ),
        if(not has(data, 'metadata'), map('metadata', metadata), map())
    )
FROM ${ANALYTICS_DB_DATABASE_NAME}.dataset_items
WHERE last_updated_at < now64(9);

-- changeset thiagohora:data_migration_dataset_items_data_field
-- version 1.0.3

-- Step 1: Replace the ${ANALYTICS_DB_DATABASE_NAME} variable with the actual database name.

-- Step 2: Run the following SQL to determine the start time of the migration and remove newly inserted data, since the new data fields are populated in newly inserted rows.

SELECT now64() as start_time;

-- Step 3: Run the following SQL to determine the number of rows that will be updated. Ps: Replace the :start_time with the value obtained in the previous step.

SELECT
    count(id)
FROM (
         SELECT
             id,
             last_updated_at
         FROM ${ANALYTICS_DB_DATABASE_NAME}.dataset_items
         ORDER BY id DESC, last_updated_at DESC
         LIMIT 1 BY id
     ) AS items
WHERE last_updated_at < :start_time;

-- Step 4: Run the following SQL to update the rows until no rows are affected (Repeat it as long as it's necessary).
-- Ps: Replace the:start_time with the value obtained in the first step and the :limit with a reasonable value. We recommend values between 1000 and 10000.
-- If the number of total dataset items is bigger than 100K, make sure to insert pauses of 1 or 2 minutes after every 50K rows updated.

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
WHERE id IN (
    SELECT
        id
    FROM (
        SELECT
            id,
            last_updated_at
        FROM ${ANALYTICS_DB_DATABASE_NAME}.dataset_items
        ORDER BY id DESC, last_updated_at DESC
        LIMIT 1 BY id
    ) AS items
    WHERE last_updated_at < :start_time
    LIMIT :limit
);

-- rollback empty
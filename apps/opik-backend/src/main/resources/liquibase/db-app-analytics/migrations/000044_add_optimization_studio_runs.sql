--liquibase formatted sql
--changeset jacquesverre:000044_add_optimization_studio_runs
--comment: Create optimization_studio_runs table for tracking optimization runs

CREATE TABLE IF NOT EXISTS ${ANALYTICS_DB_DATABASE_NAME}.optimization_studio_runs ON CLUSTER '{cluster}'
(
    id              FixedString(36),
    workspace_id    String,
    dataset_id      FixedString(36),
    optimization_id FixedString(36),
    name            String,
    prompt          String,
    algorithm       Enum8('evolutionary' = 0, 'hierarchical_reflective' = 1),
    metric          String,
    status          Enum8('running' = 0, 'completed' = 1, 'failed' = 2, 'cancelled' = 3),
    metadata        String,
    created_at      DateTime64(9, 'UTC') DEFAULT now64(9),
    last_updated_at DateTime64(9, 'UTC') DEFAULT now64(9),
    created_by      String,
    last_updated_by String
)
ENGINE = ReplicatedReplacingMergeTree('/clickhouse/tables/{shard}/${ANALYTICS_DB_DATABASE_NAME}/optimization_studio_runs', '{replica}', last_updated_at)
ORDER BY (workspace_id, dataset_id, id)
SETTINGS index_granularity = 8192;

--rollback DROP TABLE IF EXISTS ${ANALYTICS_DB_DATABASE_NAME}.optimization_studio_runs ON CLUSTER '{cluster}';

--liquibase formatted sql
--changeset BorisTkachenko:add_optimizations

CREATE TABLE IF NOT EXISTS ${ANALYTICS_DB_DATABASE_NAME}.optimizations
(
    workspace_id    String,
    dataset_id      FixedString(36),
    id              FixedString(36),
    name            String,
    objective_name  String,
    status          ENUM('running' = 0 , 'completed' = 1, 'cancelled' = 2),
    metadata        String,
    created_at      DateTime64(9, 'UTC') DEFAULT now64(9),
    last_updated_at DateTime64(9, 'UTC') DEFAULT now64(9),
    created_by      String,
    last_updated_by String
    )
    ENGINE = ReplicatedReplacingMergeTree('/clickhouse/tables/{shard}/${ANALYTICS_DB_DATABASE_NAME}/optimizations', '{replica}')
    ORDER BY (workspace_id, dataset_id, id)
    SETTINGS index_granularity = 8192;

--rollback DROP TABLE IF EXISTS ${ANALYTICS_DB_DATABASE_NAME}.optimizations;

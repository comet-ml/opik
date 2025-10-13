--liquibase formatted sql
--changeset jacquesverre:000045_add_optimization_studio_logs
--comment: Create optimization_studio_logs table for tracking optimization run logs

CREATE TABLE IF NOT EXISTS ${ANALYTICS_DB_DATABASE_NAME}.optimization_studio_logs ON CLUSTER '{cluster}'
(
    timestamp    DateTime64(9, 'UTC') DEFAULT now64(9),
    workspace_id String,
    run_id       FixedString(36),
    level        Enum8('TRACE' = 0, 'DEBUG' = 1, 'INFO' = 2, 'WARN' = 3, 'ERROR' = 4),
    message      String,
    markers      Map(String, String)
)
ENGINE = ReplicatedMergeTree('/clickhouse/tables/{shard}/${ANALYTICS_DB_DATABASE_NAME}/optimization_studio_logs', '{replica}')
ORDER BY (workspace_id, run_id, timestamp)
TTL toDateTime(timestamp + toIntervalMonth(6))
SETTINGS index_granularity = 8192;

--rollback DROP TABLE IF EXISTS ${ANALYTICS_DB_DATABASE_NAME}.optimization_studio_logs ON CLUSTER '{cluster}';

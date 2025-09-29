--liquibase formatted sql
--changeset thiaghora:create_logs_table_to_store_webhook_logs
--comment: Create logs table for tracking webhook processing logs

CREATE TABLE IF NOT EXISTS ${ANALYTICS_DB_DATABASE_NAME}.logs ON CLUSTER '{cluster}'
(
    `timestamp` DateTime64(9, 'UTC') DEFAULT now64(9),
    `workspace_id` String,
    `level` Enum8('TRACE' = 0, 'DEBUG' = 1, 'INFO' = 2, 'WARN' = 3, 'ERROR' = 4),
    `message` String,
    `markers` Map(String, String)
)
ENGINE = ReplicatedMergeTree('/clickhouse/tables/{shard}/${ANALYTICS_DB_DATABASE_NAME}/logs', '{replica}')
ORDER BY (workspace_id, timestamp)
TTL toDateTime(timestamp + toIntervalMonth(6))
SETTINGS index_granularity = 8192;

--rollback DROP TABLE IF EXISTS ${ANALYTICS_DB_DATABASE_NAME}.logs ON CLUSTER '{cluster}';

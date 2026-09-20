--liquibase formatted sql
--changeset peppa:000125_create_system_metrics
--comment: Store application process, HTTP and filesystem metrics independently from traces

CREATE TABLE IF NOT EXISTS ${ANALYTICS_DB_DATABASE_NAME}.system_metrics ON CLUSTER '{cluster}'
(
    sample_id           UUID,
    workspace_id        String,
    project_id          FixedString(36),
    service_name        LowCardinality(String),
    service_instance_id String,
    agent_id            LowCardinality(String) DEFAULT '',
    metric_name         LowCardinality(String),
    unit                LowCardinality(String),
    timestamp           DateTime64(3, 'UTC'),
    value               Float64,
    attributes          Map(String, String) DEFAULT map(),
    received_at         DateTime64(3, 'UTC') DEFAULT now64(3)
)
ENGINE = ReplicatedReplacingMergeTree(
    '/clickhouse/tables/{shard}/${ANALYTICS_DB_DATABASE_NAME}/system_metrics',
    '{replica}',
    received_at
)
PARTITION BY toYYYYMM(timestamp)
ORDER BY (workspace_id, project_id, service_instance_id, metric_name, timestamp, sample_id)
TTL toDateTime(timestamp + INTERVAL 30 DAY)
SETTINGS index_granularity = 8192;

--rollback DROP TABLE IF EXISTS ${ANALYTICS_DB_DATABASE_NAME}.system_metrics ON CLUSTER '{cluster}';

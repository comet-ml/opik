--liquibase formatted sql
--changeset thiagohora:create_automation_rule_evaluator_logs_table

CREATE TABLE IF NOT EXISTS ${ANALYTICS_DB_DATABASE_NAME}.automation_rule_evaluator_logs (
    timestamp DateTime64(9, 'UTC') DEFAULT now64(9),
    workspace_id String,
    rule_id  FixedString(36),
    level Enum8('TRACE'=0, 'DEBUG'=1, 'INFO'=2, 'WARM'=3, 'ERROR'=4),
    message String,
    markers Map(String, String)
)
ENGINE = MergeTree()
ORDER BY (workspace_id, rule_id, timestamp)
TTL toDateTime(timestamp + INTERVAL 6 MONTH);

--rollback DROP TABLE IF EXISTS ${ANALYTICS_DB_DATABASE_NAME}.automation_rule_evaluator_logs;

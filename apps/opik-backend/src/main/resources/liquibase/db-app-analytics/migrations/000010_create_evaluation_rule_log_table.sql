--liquibase formatted sql
--changeset thiagohora:000010_create_automation_rule_log_table

CREATE TABLE IF NOT EXISTS ${ANALYTICS_DB_DATABASE_NAME}.automation_rule_evaluator_logs (
    timestamp DateTime64(9, 'UTC') DEFAULT now64(9),
    workspace_id String,
    rule_id  FixedString(36),
    level Enum8('INFO'=0, 'ERROR'=1, 'WARN'=2, 'DEBUG'=3, 'TRACE'=4),
    message String,
    extra Map(String, String),
    INDEX idx_workspace_rule_id (workspace_id, rule_id) TYPE bloom_filter(0.01)
)
ENGINE = MergeTree()
ORDER BY (timestamp, workspace_id, rule_id, level)
TTL toDateTime(timestamp + INTERVAL 6 MONTH);

--rollback DROP TABLE IF EXISTS ${ANALYTICS_DB_DATABASE_NAME}.automation_rule_evaluator_logs;

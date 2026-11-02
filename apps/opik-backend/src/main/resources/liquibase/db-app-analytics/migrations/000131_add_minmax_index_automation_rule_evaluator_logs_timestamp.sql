--liquibase formatted sql
--changeset guys:000131_add_minmax_index_automation_rule_evaluator_logs_timestamp
--comment: Add minmax skip index on automation_rule_evaluator_logs.timestamp. The table is unpartitioned and sorted by (workspace_id, rule_id, timestamp), so a timestamp-range filter reads granules from nearly every part instead of only the parts covering the window. Apply after prior mutations complete: SELECT * FROM system.mutations WHERE is_done = 0 AND table = 'automation_rule_evaluator_logs'.

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.automation_rule_evaluator_logs ON CLUSTER '{cluster}'
    ADD INDEX IF NOT EXISTS idx_automation_rule_evaluator_logs_timestamp timestamp TYPE minmax GRANULARITY 1;

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.automation_rule_evaluator_logs ON CLUSTER '{cluster}'
    MATERIALIZE INDEX idx_automation_rule_evaluator_logs_timestamp;

--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.automation_rule_evaluator_logs ON CLUSTER '{cluster}' DROP INDEX IF EXISTS idx_automation_rule_evaluator_logs_timestamp;

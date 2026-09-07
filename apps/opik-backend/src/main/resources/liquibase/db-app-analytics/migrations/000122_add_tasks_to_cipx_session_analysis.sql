--liquibase formatted sql
--changeset andriid:000122_add_tasks_to_cipx_session_analysis
--comment: Task tiling and chapter summaries for the cost API session analysis

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.cipx_session_analysis ON CLUSTER '{cluster}'
    ADD COLUMN IF NOT EXISTS `segments.summary`      Array(String),
    ADD COLUMN IF NOT EXISTS `tasks.start_turn`      Array(UInt32),
    ADD COLUMN IF NOT EXISTS `tasks.end_turn`        Array(UInt32),
    ADD COLUMN IF NOT EXISTS `tasks.first_trace_id`  Array(FixedString(36)),
    ADD COLUMN IF NOT EXISTS `tasks.last_trace_id`   Array(FixedString(36)),
    ADD COLUMN IF NOT EXISTS `tasks.name`            Array(String),
    ADD COLUMN IF NOT EXISTS `tasks.summary`         Array(String),
    ADD COLUMN IF NOT EXISTS session_summary         String DEFAULT '';

--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.cipx_session_analysis ON CLUSTER '{cluster}' DROP COLUMN IF EXISTS `segments.summary`, DROP COLUMN IF EXISTS `tasks.start_turn`, DROP COLUMN IF EXISTS `tasks.end_turn`, DROP COLUMN IF EXISTS `tasks.first_trace_id`, DROP COLUMN IF EXISTS `tasks.last_trace_id`, DROP COLUMN IF EXISTS `tasks.name`, DROP COLUMN IF EXISTS `tasks.summary`, DROP COLUMN IF EXISTS session_summary;


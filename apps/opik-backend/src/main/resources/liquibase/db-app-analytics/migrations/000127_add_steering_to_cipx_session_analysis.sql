--liquibase formatted sql
--changeset petrot:000127_add_steering_to_cipx_session_analysis
--comment: Steering turn detection for the cost API session analysis

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.cipx_session_analysis ON CLUSTER '{cluster}'
    ADD COLUMN IF NOT EXISTS steering_checked   UInt8 DEFAULT 0,
    ADD COLUMN IF NOT EXISTS prompt_turns        UInt32 DEFAULT 0,
    ADD COLUMN IF NOT EXISTS `steering.turn`      Array(UInt32),
    ADD COLUMN IF NOT EXISTS `steering.trace_id`  Array(FixedString(36)),
    ADD COLUMN IF NOT EXISTS `steering.kind`      Array(LowCardinality(String)),
    ADD COLUMN IF NOT EXISTS `steering.summary`   Array(String);

--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.cipx_session_analysis ON CLUSTER '{cluster}' DROP COLUMN IF EXISTS steering_checked, DROP COLUMN IF EXISTS prompt_turns, DROP COLUMN IF EXISTS `steering.turn`, DROP COLUMN IF EXISTS `steering.trace_id`, DROP COLUMN IF EXISTS `steering.kind`, DROP COLUMN IF EXISTS `steering.summary`;


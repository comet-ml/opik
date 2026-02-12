--liquibase formatted sql
--changeset vincentkoc:000057_add_error_columns_to_feedback_scores

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.feedback_scores ON CLUSTER '{cluster}'
    ADD COLUMN IF NOT EXISTS `error` UInt8 DEFAULT 0 AFTER `reason`;

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.feedback_scores ON CLUSTER '{cluster}'
    ADD COLUMN IF NOT EXISTS `error_reason` String DEFAULT '' AFTER `error`;

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.authored_feedback_scores ON CLUSTER '{cluster}'
    ADD COLUMN IF NOT EXISTS `error` UInt8 DEFAULT 0 AFTER `reason`;

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.authored_feedback_scores ON CLUSTER '{cluster}'
    ADD COLUMN IF NOT EXISTS `error_reason` String DEFAULT '' AFTER `error`;

--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.feedback_scores ON CLUSTER '{cluster}' DROP COLUMN IF EXISTS `error_reason`;
--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.feedback_scores ON CLUSTER '{cluster}' DROP COLUMN IF EXISTS `error`;
--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.authored_feedback_scores ON CLUSTER '{cluster}' DROP COLUMN IF EXISTS `error_reason`;
--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.authored_feedback_scores ON CLUSTER '{cluster}' DROP COLUMN IF EXISTS `error`;

--liquibase formatted sql
--changeset thiagoh:000078_add_minmax_index_authored_feedback_scores_created_at
--comment: Add minmax skip index on created_at to enable time-bounded queries on authored_feedback_scores

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.authored_feedback_scores ON CLUSTER '{cluster}'
    ADD INDEX IF NOT EXISTS idx_authored_feedback_scores_created_at created_at TYPE minmax GRANULARITY 1;

--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.authored_feedback_scores ON CLUSTER '{cluster}' DROP INDEX IF EXISTS idx_authored_feedback_scores_created_at;

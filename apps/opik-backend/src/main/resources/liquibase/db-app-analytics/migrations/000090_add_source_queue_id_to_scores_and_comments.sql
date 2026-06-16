--liquibase formatted sql
--changeset miguelg:000090_add_source_queue_id_to_scores_and_comments
--comment: Add source_queue_id to authored_feedback_scores and comments for annotation queue provenance tracking (OPIK-6791)

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.authored_feedback_scores ON CLUSTER '{cluster}'
    ADD COLUMN IF NOT EXISTS source_queue_id Nullable(FixedString(36)) DEFAULT NULL;

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.comments ON CLUSTER '{cluster}'
    ADD COLUMN IF NOT EXISTS source_queue_id Nullable(FixedString(36)) DEFAULT NULL;

--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.authored_feedback_scores ON CLUSTER '{cluster}' DROP COLUMN IF EXISTS source_queue_id;
--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.comments ON CLUSTER '{cluster}' DROP COLUMN IF EXISTS source_queue_id;

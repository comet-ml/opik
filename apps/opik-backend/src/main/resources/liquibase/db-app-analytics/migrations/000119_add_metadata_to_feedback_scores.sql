--liquibase formatted sql
--changeset trakshan:000119_add_metadata_to_feedback_scores
--comment: Add metadata column to feedback_scores and authored_feedback_scores for evaluator provenance (OPIK-7980)
--
-- Stores opaque caller-supplied JSON (e.g. evaluator revision/fingerprint) alongside a score.
-- Empty string means no metadata was supplied (rows written before the column existed).

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.feedback_scores ON CLUSTER '{cluster}'
    ADD COLUMN IF NOT EXISTS metadata String DEFAULT '';

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.authored_feedback_scores ON CLUSTER '{cluster}'
    ADD COLUMN IF NOT EXISTS metadata String DEFAULT '';

--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.authored_feedback_scores ON CLUSTER '{cluster}' DROP COLUMN IF EXISTS metadata;
--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.feedback_scores ON CLUSTER '{cluster}' DROP COLUMN IF EXISTS metadata;

--liquibase formatted sql
--changeset trakshan:000130_add_evaluator_revision_to_feedback_scores
--comment: Add evaluator_revision to feedback_scores and authored_feedback_scores (OPIK-7980)

-- Caller-supplied revision of the evaluator that produced a score, so stored scores can be traced back to
-- the evaluator version. Empty string means none was set, which is also what rows written before this
-- column existed read.
ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.feedback_scores ON CLUSTER '{cluster}'
    ADD COLUMN IF NOT EXISTS evaluator_revision String DEFAULT '';

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.authored_feedback_scores ON CLUSTER '{cluster}'
    ADD COLUMN IF NOT EXISTS evaluator_revision String DEFAULT '';

--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.authored_feedback_scores ON CLUSTER '{cluster}' DROP COLUMN IF EXISTS evaluator_revision;
--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.feedback_scores ON CLUSTER '{cluster}' DROP COLUMN IF EXISTS evaluator_revision;

--liquibase formatted sql
--changeset thiagohora:000083_materialize_authored_feedback_scores_skip_indexes
--comment: Materialize skip indexes on authored_feedback_scores (~251M rows). Apply after 000082 mutations complete: SELECT * FROM system.mutations WHERE is_done = 0 AND table = 'authored_feedback_scores'.

-- Materialize index from 000078 (idx_authored_feedback_scores_created_at was added without materialization)
ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.authored_feedback_scores ON CLUSTER '{cluster}'
    MATERIALIZE INDEX idx_authored_feedback_scores_created_at;

--rollback empty

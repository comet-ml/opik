--liquibase formatted sql
--changeset opik:000035_add_multi_scoring_support

-- Add score_id column to uniquely identify each score instance
ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.feedback_scores ON CLUSTER '{cluster}'
    ADD COLUMN score_id FixedString(36) DEFAULT generateUUIDv4();

-- Update the ORDER BY clause to include score_id for proper deduplication
ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.feedback_scores ON CLUSTER '{cluster}'
    MODIFY ORDER BY (workspace_id, project_id, entity_type, entity_id, name, score_id);

-- Add index for efficient querying by score_id
ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.feedback_scores ON CLUSTER '{cluster}'
    ADD INDEX idx_score_id score_id TYPE minmax GRANULARITY 1;

--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.feedback_scores ON CLUSTER '{cluster}' DROP INDEX idx_score_id;
--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.feedback_scores ON CLUSTER '{cluster}' MODIFY ORDER BY (workspace_id, project_id, entity_type, entity_id, name);
--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.feedback_scores ON CLUSTER '{cluster}' DROP COLUMN score_id;
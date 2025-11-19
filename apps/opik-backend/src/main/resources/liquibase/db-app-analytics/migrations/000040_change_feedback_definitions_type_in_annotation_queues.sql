--liquibase formatted sql
--changeset borystkachenko:000040_change_feedback_definitions_type_in_annotation_queues
--comment: Change feedback_definitions column type from Array(FixedString(36)) to Array(String) to support name-based references

-- Change feedback_definitions column type to support string-based feedback definition names
-- This allows referencing feedback definitions by their name rather than UUID
ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.annotation_queues ON CLUSTER '{cluster}'
    MODIFY COLUMN feedback_definitions Array(String);

--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.annotation_queues ON CLUSTER '{cluster}' MODIFY COLUMN feedback_definitions Array(FixedString(36));


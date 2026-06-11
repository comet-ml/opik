--liquibase formatted sql
--changeset miguelg:000089_add_lock_timeout_seconds_to_annotation_queues
--comment: Add lock_timeout_seconds column to annotation_queues for claim TTL configuration (OPIK-6720)

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.annotation_queues ON CLUSTER '{cluster}'
    ADD COLUMN IF NOT EXISTS lock_timeout_seconds UInt32 DEFAULT 300;

--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.annotation_queues ON CLUSTER '{cluster}' DROP COLUMN IF EXISTS lock_timeout_seconds;

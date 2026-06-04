--liquibase formatted sql
--changeset miguelg:000088_add_lock_timeout_minutes_to_annotation_queues
--comment: Add lock_timeout_minutes column to annotation_queues for claim TTL configuration (OPIK-6720)

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.annotation_queues ON CLUSTER '{cluster}'
    ADD COLUMN IF NOT EXISTS lock_timeout_minutes UInt16 DEFAULT 5;

--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.annotation_queues ON CLUSTER '{cluster}' DROP COLUMN IF EXISTS lock_timeout_minutes;

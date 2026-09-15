--liquibase formatted sql
--changeset aliaksandrk:000119_add_source_to_annotation_queue_items
--comment: Add source column to annotation_queue_items to distinguish manually added items from ones routed by queue automation (OPIK-6303)

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.annotation_queue_items ON CLUSTER '{cluster}'
    ADD COLUMN IF NOT EXISTS source Enum8('manual' = 1, 'automated' = 2) DEFAULT 'manual';

--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.annotation_queue_items ON CLUSTER '{cluster}' DROP COLUMN IF EXISTS source;

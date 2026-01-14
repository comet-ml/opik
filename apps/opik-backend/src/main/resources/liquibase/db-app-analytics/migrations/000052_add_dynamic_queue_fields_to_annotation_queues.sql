--liquibase formatted sql
--changeset dynamic-annotation-queues:000052_add_dynamic_queue_fields_to_annotation_queues
--comment: Add queue_type and filter_criteria columns to annotation_queues table for dynamic queue support

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.annotation_queues ON CLUSTER '{cluster}'
    ADD COLUMN IF NOT EXISTS queue_type Enum8('manual' = 1, 'dynamic' = 2) DEFAULT 'manual';

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.annotation_queues ON CLUSTER '{cluster}'
    ADD COLUMN IF NOT EXISTS filter_criteria String DEFAULT '';

--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.annotation_queues ON CLUSTER '{cluster}' DROP COLUMN IF EXISTS queue_type;
--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.annotation_queues ON CLUSTER '{cluster}' DROP COLUMN IF EXISTS filter_criteria;

--liquibase formatted sql
--changeset miguelg:000085_add_annotators_per_item_to_annotation_queues
--comment: Add annotators_per_item column to annotation_queues for multi-annotator single-pass mode (OPIK-6525)

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.annotation_queues ON CLUSTER '{cluster}'
    ADD COLUMN IF NOT EXISTS annotators_per_item UInt16 DEFAULT 1;

--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.annotation_queues ON CLUSTER '{cluster}' DROP COLUMN IF EXISTS annotators_per_item;


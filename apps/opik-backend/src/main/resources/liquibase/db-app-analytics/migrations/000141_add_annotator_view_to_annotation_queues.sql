--liquibase formatted sql
--changeset andriidudar:000141_add_annotator_view_to_annotation_queues
--comment: Add annotator_view column to annotation_queues for the custom view shown to annotators (OPIK-8809)

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.annotation_queues ON CLUSTER '{cluster}'
    ADD COLUMN IF NOT EXISTS annotator_view String DEFAULT '';

--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.annotation_queues ON CLUSTER '{cluster}' DROP COLUMN IF EXISTS annotator_view;

--liquibase formatted sql
--changeset thiaghora:000057_add_project_id_to_experiment_items
--comment: Add project_id column to experiment_items for denormalization optimization (OPIK-4381)

-- Add nullable project_id column to store trace project association
ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.experiment_items ON CLUSTER '{cluster}'
    ADD COLUMN IF NOT EXISTS project_id Nullable(FixedString(36));
--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.experiment_items ON CLUSTER '{cluster}' DROP COLUMN IF EXISTS project_id;


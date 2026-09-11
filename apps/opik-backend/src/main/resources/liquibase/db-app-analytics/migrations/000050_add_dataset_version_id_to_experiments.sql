--liquibase formatted sql
--changeset idoberko2:000050_add_dataset_version_id_to_experiments
--comment: Add dataset_version_id column to experiments table to link experiments to specific dataset versions

-- Add dataset_version_id column to experiments table
-- Default empty string for backward compatibility with existing experiments
ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.experiments ON CLUSTER '{cluster}'
    ADD COLUMN IF NOT EXISTS dataset_version_id String DEFAULT '';

--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.experiments ON CLUSTER '{cluster}' DROP COLUMN IF EXISTS dataset_version_id;


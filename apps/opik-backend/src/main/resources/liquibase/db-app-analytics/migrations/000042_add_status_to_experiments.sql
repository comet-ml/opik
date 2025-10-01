--liquibase formatted sql
--changeset idoberko2:000042_add_status_to_experiments
--comment: Add status field to experiments table to track experiment state

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.experiments ON CLUSTER '{cluster}'
    ADD COLUMN IF NOT EXISTS status ENUM('unknown' = 0, 'running' = 1, 'completed' = 2, 'cancelled' = 3);

--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.experiments ON CLUSTER '{cluster}' DROP COLUMN IF EXISTS status;

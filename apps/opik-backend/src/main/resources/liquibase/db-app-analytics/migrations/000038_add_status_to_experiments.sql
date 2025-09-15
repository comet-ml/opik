--liquibase formatted sql
--changeset idoberko2:000038_add_status_to_experiments
--comment: Add status field to experiments table to track experiment state

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.experiments ON CLUSTER '{cluster}'
    ADD COLUMN IF NOT EXISTS status ENUM('running' = 0, 'completed' = 1, 'cancelled' = 2) DEFAULT 'running';

--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.experiments DROP COLUMN IF EXISTS status;

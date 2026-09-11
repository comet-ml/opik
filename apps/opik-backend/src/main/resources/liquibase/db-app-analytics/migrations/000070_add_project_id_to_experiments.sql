--liquibase formatted sql
--changeset thiaghora:000070_add_project_id_to_experiments
--comment: Add project_id column to experiments table for project-scoped operations (OPIK-4932)

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.experiments ON CLUSTER '{cluster}'
    ADD COLUMN IF NOT EXISTS project_id String DEFAULT '';

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.experiments ON CLUSTER '{cluster}'
    ADD INDEX IF NOT EXISTS idx_experiments_project_id project_id TYPE minmax GRANULARITY 1;

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.experiments ON CLUSTER '{cluster}'
    MATERIALIZE INDEX idx_experiments_project_id;

--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.experiments ON CLUSTER '{cluster}' DROP INDEX IF EXISTS idx_experiments_project_id;
--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.experiments ON CLUSTER '{cluster}' DROP COLUMN IF EXISTS project_id;
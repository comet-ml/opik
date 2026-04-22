--liquibase formatted sql
--changeset thiaghora:000075_add_minmax_index_optimizations_project_id
--comment: Add minmax index on project_id in optimizations table for efficient granule skipping

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.optimizations ON CLUSTER '{cluster}'
    ADD INDEX IF NOT EXISTS idx_optimizations_project_id project_id TYPE minmax GRANULARITY 1;

--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.optimizations ON CLUSTER '{cluster}' DROP INDEX IF EXISTS idx_optimizations_project_id;


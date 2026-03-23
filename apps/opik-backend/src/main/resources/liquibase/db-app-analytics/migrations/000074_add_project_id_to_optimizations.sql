--liquibase formatted sql
--changeset thiaghora:000074_add_project_id_to_optimizations
--comment: Add project_id column to optimizations table for project-scoped operations

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.optimizations ON CLUSTER '{cluster}'
    ADD COLUMN IF NOT EXISTS project_id String DEFAULT '';

--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.optimizations ON CLUSTER '{cluster}' DROP COLUMN IF EXISTS project_id;


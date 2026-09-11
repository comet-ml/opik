--liquibase formatted sql
--changeset thiagohora:add_dataset_deleted_to_optimizations

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.optimizations
    ADD COLUMN IF NOT EXISTS dataset_deleted BOOLEAN DEFAULT FALSE;

--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.optimizations DROP COLUMN IF EXISTS dataset_deleted;

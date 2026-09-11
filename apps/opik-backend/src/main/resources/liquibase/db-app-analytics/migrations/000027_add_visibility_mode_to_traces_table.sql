--liquibase formatted sql
--changeset thiagohora:000027_add_visibility_mode_to_traces_table

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces ON CLUSTER '{cluster}'
    ADD COLUMN IF NOT EXISTS visibility_mode Enum8('unknown' = 0, 'default' = 1, 'hidden' = 2) DEFAULT 'default';

--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces ON CLUSTER '{cluster}' DROP COLUMN IF EXISTS visibility_mode;

--liquibase formatted sql
--changeset thiagohora:add_thread_id_to_traces

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces ADD COLUMN thread_id String;

--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces DROP COLUMN IF EXISTS thread_id;

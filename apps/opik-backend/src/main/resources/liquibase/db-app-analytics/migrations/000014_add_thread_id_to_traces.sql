--liquibase formatted sql
--changeset thiagohora:add_thread_id_to_traces
--validCheckSum ANY
-- OPIK-7445: `ADD COLUMN` here gained `IF NOT EXISTS` so a lost/partial Liquibase ledger
-- degrades to a no-op replay instead of crashlooping the replica on DUPLICATE_COLUMN.
-- Editing an applied changeset changes its checksum, so already-migrated deployments must
-- accept the stored pre-edit value or `liquibase update` fails validation before running.

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces ADD COLUMN IF NOT EXISTS thread_id String;

--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces DROP COLUMN IF EXISTS thread_id;

--liquibase formatted sql
--changeset thiagohora:add_thread_id_to_traces
--validCheckSum ANY
-- ⚠️  EDITED AFTER RELEASE (OPIK-7445) — this changeset was already applied in production when
-- it was modified. Migrations are normally immutable; this is a deliberate, reviewed exception.
--
-- What changed: `ADD COLUMN` gained `IF NOT EXISTS`, so a lost or partial Liquibase ledger
-- replays as a no-op instead of crashlooping the replica on `Code: 15 DUPLICATE_COLUMN`.
--
-- Why `--validCheckSum ANY` is required: editing an applied changeset changes its checksum, and
-- `liquibase update` validates stored checksums before executing anything. Without the waiver,
-- every already-migrated deployment fails to start. Never remove one directive without the other.
--
-- Do NOT treat this file as a precedent for editing other applied migrations.

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces ADD COLUMN IF NOT EXISTS thread_id String;

--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces DROP COLUMN IF EXISTS thread_id;

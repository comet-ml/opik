--liquibase formatted sql
--changeset andrescrz:000091_add_id_at_to_traces
--comment: Add id_at materialized column to traces for future weekly partitioning

-- Adds id_at so retention/read predicates can target the future partition expression now
-- and need no change at cutover. UTC matches the table's other temporal columns and keeps
-- week boundaries independent of the server timezone.
-- DateTime (seconds) is enough: discards sub-second, and it halves the column vs DateTime64.
ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces ON CLUSTER '{cluster}'
    ADD COLUMN IF NOT EXISTS id_at DateTime('UTC') MATERIALIZED UUIDv7ToDateTime(toUUID(id));

--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces ON CLUSTER '{cluster}' DROP COLUMN IF EXISTS id_at;

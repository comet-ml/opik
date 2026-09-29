--liquibase formatted sql
--changeset avinahradau:000119_add_device_id_to_cipx_trace_identities
--comment: Add device_id (enrolled machine identity from the validated CIPX device token) to cipx_trace_identities

-- Written from the token the ingest request presented, which cost-api validated, and never from the trace's
-- own metadata: the point of the column is an identity a client cannot forge. Plain String, not
-- LowCardinality: one value per enrolled machine, so the cardinality grows with the fleet.
ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.cipx_trace_identities ON CLUSTER '{cluster}'
    ADD COLUMN IF NOT EXISTS device_id String DEFAULT '';

--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.cipx_trace_identities ON CLUSTER '{cluster}' DROP COLUMN IF EXISTS device_id;

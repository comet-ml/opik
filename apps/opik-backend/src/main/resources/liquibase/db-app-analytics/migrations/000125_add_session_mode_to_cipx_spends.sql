--liquibase formatted sql
--changeset petrotiurin:000125_add_session_mode_to_cipx_spends
--comment: Persist how the harness invoked the call on cipx_spends
--
-- Per call: how the harness invoked it (interactive, background, headless, unknown). Not every
-- provider stamps it; '' covers those, and every row written before this column or this backend rolled out.

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.cipx_spends ON CLUSTER '{cluster}'
    ADD COLUMN IF NOT EXISTS session_mode LowCardinality(String) DEFAULT '';

--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.cipx_spends ON CLUSTER '{cluster}' DROP COLUMN IF EXISTS session_mode;

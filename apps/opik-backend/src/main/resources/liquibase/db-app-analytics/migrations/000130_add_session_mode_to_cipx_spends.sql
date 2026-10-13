--liquibase formatted sql
--changeset petrot:000130_add_session_mode_to_cipx_spends
--comment: Persist how the harness invoked the call on cipx_spends
--
-- Per call: how the harness invoked it (interactive, background, headless, unknown). Not every
-- provider stamps it; '' covers those, and every row written before this column or this backend rolled out.

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.cipx_spends ON CLUSTER '{cluster}'
    ADD COLUMN IF NOT EXISTS session_mode LowCardinality(String) DEFAULT '';

--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.cipx_spends ON CLUSTER '{cluster}' DROP COLUMN IF EXISTS session_mode;

--changeset petrot:000130_add_headless_to_cipx_session_analysis
--comment: Keep the headless flag on the cost API session analysis

-- `headless` = the analysis found the session headless; readers show its tasks as automation.
-- `tasks.kind` keeps the model's own kind. Rows written before this column existed read 0.
ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.cipx_session_analysis ON CLUSTER '{cluster}'
    ADD COLUMN IF NOT EXISTS headless UInt8 DEFAULT 0;

--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.cipx_session_analysis ON CLUSTER '{cluster}' DROP COLUMN IF EXISTS headless;

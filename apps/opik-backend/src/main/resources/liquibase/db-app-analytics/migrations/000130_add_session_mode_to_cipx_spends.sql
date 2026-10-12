--liquibase formatted sql
--changeset petrot:000130_add_session_mode_to_cipx_spends
--comment: Persist how the harness invoked the call on cipx_spends
--
-- Per call: how the harness invoked it (interactive, background, headless, unknown). Not every
-- provider stamps it; '' covers those, and every row written before this column or this backend rolled out.

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.cipx_spends ON CLUSTER '{cluster}'
    ADD COLUMN IF NOT EXISTS session_mode LowCardinality(String) DEFAULT '';

--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.cipx_spends ON CLUSTER '{cluster}' DROP COLUMN IF EXISTS session_mode;

--changeset petrot:000130_add_model_kind_and_headless_to_cipx_session_analysis
--comment: Keep the model's own task kind and the headless override flag on the cost API session analysis

-- `tasks.model_kind` is the kind the model chose per task, parallel to `tasks.kind` (the final kind, after the code override).
-- `headless` marks sessions found headless, where every task kind was overridden to automation.
-- Rows written before these columns existed read '' per task / 0.
ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.cipx_session_analysis ON CLUSTER '{cluster}'
    ADD COLUMN IF NOT EXISTS `tasks.model_kind` Array(LowCardinality(String)),
    ADD COLUMN IF NOT EXISTS headless UInt8 DEFAULT 0;

--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.cipx_session_analysis ON CLUSTER '{cluster}' DROP COLUMN IF EXISTS `tasks.model_kind`, DROP COLUMN IF EXISTS headless;

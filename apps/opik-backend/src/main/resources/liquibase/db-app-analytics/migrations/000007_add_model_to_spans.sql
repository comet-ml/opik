--liquibase formatted sql
--changeset BorisTkachenko:add_model_to_spans

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.spans
    ADD COLUMN IF NOT EXISTS model String DEFAULT '',
    ADD COLUMN IF NOT EXISTS provider String DEFAULT '',
    ADD COLUMN IF NOT EXISTS total_estimated_cost Decimal64(8),
    ADD COLUMN IF NOT EXISTS total_estimated_cost_version String DEFAULT '';

--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.spans DROP COLUMN IF EXISTS model, DROP COLUMN IF EXISTS provider, DROP COLUMN IF EXISTS total_estimated_cost, DROP COLUMN IF EXISTS total_estimated_cost_version;

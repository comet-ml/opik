--liquibase formatted sql
--changeset BorisTkachenko:000008_add_error_info_to_spans_and_traces

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.spans
    ADD COLUMN IF NOT EXISTS error_info String DEFAULT '';

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces
    ADD COLUMN IF NOT EXISTS error_info String DEFAULT '';

--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.spans DROP COLUMN IF EXISTS error_info;
--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces DROP COLUMN IF EXISTS error_info;

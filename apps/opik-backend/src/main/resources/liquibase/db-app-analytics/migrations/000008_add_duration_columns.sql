--liquibase formatted sql
--changeset thiagohora:add_duration_columns

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.spans
    ADD COLUMN IF NOT EXISTS duration Nullable(Float64) MATERIALIZED if(end_time IS NOT NULL, (dateDiff('microsecond', start_time, end_time) / 1000.0), NULL);

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces
    ADD COLUMN IF NOT EXISTS duration Nullable(Float64) MATERIALIZED if(end_time IS NOT NULL, (dateDiff('microsecond', start_time, end_time) / 1000.0), NULL);

--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.spans DROP COLUMN IF EXISTS duration;
--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces DROP COLUMN IF EXISTS duration;

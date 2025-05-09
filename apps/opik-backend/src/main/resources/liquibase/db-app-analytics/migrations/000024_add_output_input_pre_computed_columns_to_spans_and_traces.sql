--liquibase formatted sql
--changeset thiagohora:add_output_input_pre_computed_columns_to_spans_and_traces

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces ON CLUSTER '{cluster}'
    ADD COLUMN IF NOT EXISTS has_input BOOL MATERIALIZED notEmpty(input),
    ADD COLUMN IF NOT EXISTS has_output BOOL MATERIALIZED notEmpty(output),
    ADD COLUMN IF NOT EXISTS has_metadata BOOL MATERIALIZED notEmpty(metadata);

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.spans ON CLUSTER '{cluster}'
    ADD COLUMN IF NOT EXISTS has_input BOOL MATERIALIZED notEmpty(input),
    ADD COLUMN IF NOT EXISTS has_output BOOL MATERIALIZED notEmpty(output),
    ADD COLUMN IF NOT EXISTS has_metadata BOOL MATERIALIZED notEmpty(metadata);

--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.spans ON CLUSTER '{cluster}' DROP COLUMN IF EXISTS has_input, DROP COLUMN IF EXISTS has_output, DROP COLUMN IF EXISTS has_metadata;
--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces ON CLUSTER '{cluster}' DROP COLUMN IF EXISTS has_input, DROP COLUMN IF EXISTS has_output, DROP COLUMN IF EXISTS has_metadata;

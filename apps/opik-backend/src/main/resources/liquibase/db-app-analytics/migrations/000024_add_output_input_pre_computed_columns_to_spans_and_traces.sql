--liquibase formatted sql
--changeset thiagohora:000024_add_output_input_pre_computed_columns_to_spans_and_traces

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces ON CLUSTER '{cluster}'
    ADD COLUMN IF NOT EXISTS input_length UInt64 MATERIALIZED length(input),
    ADD COLUMN IF NOT EXISTS output_length UInt64 MATERIALIZED length(output),
    ADD COLUMN IF NOT EXISTS metadata_length UInt64 MATERIALIZED length(metadata);

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.spans ON CLUSTER '{cluster}'
    ADD COLUMN IF NOT EXISTS input_length UInt64 MATERIALIZED length(input),
    ADD COLUMN IF NOT EXISTS output_length UInt64 MATERIALIZED length(output),
    ADD COLUMN IF NOT EXISTS metadata_length UInt64 MATERIALIZED length(metadata);

--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.spans ON CLUSTER '{cluster}' DROP COLUMN IF EXISTS input_length, DROP COLUMN IF EXISTS output_length, DROP COLUMN IF EXISTS metadata_length;
--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces ON CLUSTER '{cluster}' DROP COLUMN IF EXISTS input_length, DROP COLUMN IF EXISTS output_length, DROP COLUMN IF EXISTS metadata_length;

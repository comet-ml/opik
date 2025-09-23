--liquibase formatted sql
--changeset thiagohora:000041_add_truncated_input_output_to_spans_and_traces_tables

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces ON CLUSTER '{cluster}'
    -- 10001 is used as the truncation threshold (10KB + 1 byte) for input/output columns
    ADD COLUMN IF NOT EXISTS truncation_threshold UInt64 DEFAULT 10001,
    ADD COLUMN IF NOT EXISTS truncated_input String MATERIALIZED if(length(input) >= truncation_threshold, substring(input, 1, truncation_threshold), input),
    ADD COLUMN IF NOT EXISTS truncated_output String MATERIALIZED if(length(output) >= truncation_threshold, substring(output, 1, truncation_threshold), output);

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.spans ON CLUSTER '{cluster}'
    -- 10001 is used as the truncation threshold (10KB + 1 byte) for input/output columns
    ADD COLUMN IF NOT EXISTS truncation_threshold UInt64 DEFAULT 10001,
    ADD COLUMN IF NOT EXISTS truncated_input String MATERIALIZED if(length(input) >= truncation_threshold, substring(input, 1, truncation_threshold), input),
    ADD COLUMN IF NOT EXISTS truncated_output String MATERIALIZED if(length(output) >= truncation_threshold, substring(output, 1, truncation_threshold), output);

--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.spans ON CLUSTER '{cluster}' DROP COLUMN IF EXISTS truncation_threshold, DROP COLUMN IF EXISTS truncated_input, DROP COLUMN IF EXISTS truncated_output;
--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces ON CLUSTER '{cluster}' DROP COLUMN IF EXISTS truncation_threshold, DROP COLUMN IF EXISTS truncated_input, DROP COLUMN IF EXISTS truncated_output;

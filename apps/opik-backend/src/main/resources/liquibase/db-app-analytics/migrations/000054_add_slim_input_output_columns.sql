--liquibase formatted sql
--changeset daniela:000054_add_slim_input_output_columns
--comment: Add input_slim and output_slim columns for structure-preserving JSON truncation

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces ON CLUSTER '{cluster}'
    ADD COLUMN IF NOT EXISTS input_slim String DEFAULT '',
    ADD COLUMN IF NOT EXISTS output_slim String DEFAULT '';

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.spans ON CLUSTER '{cluster}'
    ADD COLUMN IF NOT EXISTS input_slim String DEFAULT '',
    ADD COLUMN IF NOT EXISTS output_slim String DEFAULT '';

--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces ON CLUSTER '{cluster}' DROP COLUMN IF EXISTS input_slim, DROP COLUMN IF EXISTS output_slim;
--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.spans ON CLUSTER '{cluster}' DROP COLUMN IF EXISTS input_slim, DROP COLUMN IF EXISTS output_slim;

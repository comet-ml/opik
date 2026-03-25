--liquibase formatted sql
--changeset borystkachenko:000057_add_ttft_column_to_traces_and_spans
--comment: Add ttft (time to first token) column to traces and spans tables

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces ON CLUSTER '${ANALYTICS_DB_CLUSTER_NAME}'
    ADD COLUMN IF NOT EXISTS `ttft` Nullable(Float64);

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.spans ON CLUSTER '${ANALYTICS_DB_CLUSTER_NAME}'
    ADD COLUMN IF NOT EXISTS `ttft` Nullable(Float64);

--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces ON CLUSTER '${ANALYTICS_DB_CLUSTER_NAME}' DROP COLUMN IF EXISTS `ttft`;
--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.spans ON CLUSTER '${ANALYTICS_DB_CLUSTER_NAME}' DROP COLUMN IF EXISTS `ttft`;

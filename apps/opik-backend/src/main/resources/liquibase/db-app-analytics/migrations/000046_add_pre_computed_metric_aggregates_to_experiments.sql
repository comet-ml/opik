--liquibase formatted sql
--changeset jacquesverre:000046_add_pre_computed_metric_aggregates_to_experiments
--comment: Add pre_computed_metric_aggregates field to experiments table to store user-computed metrics per feedback score as JSON

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.experiments ON CLUSTER '{cluster}'
    ADD COLUMN IF NOT EXISTS pre_computed_metric_aggregates String DEFAULT '';

--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.experiments ON CLUSTER '{cluster}' DROP COLUMN IF EXISTS pre_computed_metric_aggregates;

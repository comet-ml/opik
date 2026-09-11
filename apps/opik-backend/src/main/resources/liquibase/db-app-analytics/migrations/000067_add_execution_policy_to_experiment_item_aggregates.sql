--liquibase formatted sql
--changeset danield:000067_add_execution_policy_to_experiment_item_aggregates
--comment: Add execution_policy column to experiment_item_aggregates table for evaluation suite assertion results computation

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.experiment_item_aggregates ON CLUSTER '{cluster}'
    ADD COLUMN IF NOT EXISTS execution_policy String DEFAULT '';

--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.experiment_item_aggregates ON CLUSTER '{cluster}' DROP COLUMN IF EXISTS execution_policy;

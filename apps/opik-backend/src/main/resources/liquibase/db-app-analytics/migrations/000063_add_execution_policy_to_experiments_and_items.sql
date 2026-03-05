--liquibase formatted sql
--changeset danield:000063_add_execution_policy_to_experiments_and_items
--comment: Denormalize execution_policy to experiments (suite-level) and experiment_items (item-level) for pass_rate CTE

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.experiments ON CLUSTER '{cluster}'
    ADD COLUMN IF NOT EXISTS execution_policy String DEFAULT '';

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.experiment_items ON CLUSTER '{cluster}'
    ADD COLUMN IF NOT EXISTS execution_policy String DEFAULT '';

--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.experiments ON CLUSTER '{cluster}' DROP COLUMN IF EXISTS execution_policy;
--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.experiment_items ON CLUSTER '{cluster}' DROP COLUMN IF EXISTS execution_policy;

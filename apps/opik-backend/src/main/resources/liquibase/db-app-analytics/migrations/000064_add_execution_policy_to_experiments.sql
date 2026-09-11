--liquibase formatted sql
--changeset danield:000064_add_execution_policy_to_experiments
--comment: Add execution_policy column to experiments and experiment_items tables for evaluation suite pass_rate computation

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.experiments ON CLUSTER '{cluster}'
    ADD COLUMN IF NOT EXISTS execution_policy String DEFAULT '';

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.experiment_items ON CLUSTER '{cluster}'
    ADD COLUMN IF NOT EXISTS execution_policy String DEFAULT '';

--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.experiments ON CLUSTER '{cluster}' DROP COLUMN IF EXISTS execution_policy;
--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.experiment_items ON CLUSTER '{cluster}' DROP COLUMN IF EXISTS execution_policy;

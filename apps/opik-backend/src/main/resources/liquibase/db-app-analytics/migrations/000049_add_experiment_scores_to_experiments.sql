--liquibase formatted sql
--changeset jverre:000048_add_experiment_scores_to_experiments
--comment: Add experiment_scores field to experiments table to store precomputed metrics

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.experiments ON CLUSTER '{cluster}'
    ADD COLUMN IF NOT EXISTS experiment_scores String DEFAULT '';

--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.experiments ON CLUSTER '{cluster}' DROP COLUMN IF EXISTS experiment_scores;


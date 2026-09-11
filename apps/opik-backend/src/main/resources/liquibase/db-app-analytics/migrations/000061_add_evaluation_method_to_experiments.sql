--liquibase formatted sql
--changeset JetoPistola:000061_add_evaluation_method_to_experiments
--comment: Add evaluation_method field to experiments table to distinguish how an experiment was created

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.experiments ON CLUSTER '{cluster}'
    ADD COLUMN IF NOT EXISTS evaluation_method ENUM('unknown' = 0, 'dataset' = 1, 'evaluation_suite' = 2) DEFAULT 'unknown';

--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.experiments ON CLUSTER '{cluster}' DROP COLUMN IF EXISTS evaluation_method;

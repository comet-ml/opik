--liquibase formatted sql
--changeset thiaghora:000062_add_evaluation_method_to_experiment_aggregates
--comment: Add evaluation_method field to experiment_aggregates table

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.experiment_aggregates ON CLUSTER '{cluster}'
    ADD COLUMN IF NOT EXISTS evaluation_method ENUM('unknown' = 0, 'dataset' = 1, 'evaluation_suite' = 2) DEFAULT 'unknown';

--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.experiment_aggregates ON CLUSTER '{cluster}' DROP COLUMN IF EXISTS evaluation_method;

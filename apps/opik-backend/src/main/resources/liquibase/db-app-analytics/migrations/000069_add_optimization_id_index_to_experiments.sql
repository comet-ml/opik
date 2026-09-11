--liquibase formatted sql
--changeset itamarg:000069_add_optimization_id_index_to_experiments
--comment: Add minmax index on optimization_id to speed up optimization queries that join experiments by optimization_id

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.experiments ON CLUSTER '{cluster}'
    ADD INDEX IF NOT EXISTS idx_experiments_optimization_id optimization_id TYPE minmax GRANULARITY 1;

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.experiments ON CLUSTER '{cluster}'
    MATERIALIZE INDEX idx_experiments_optimization_id;

--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.experiments ON CLUSTER '{cluster}' DROP INDEX IF EXISTS idx_experiments_optimization_id;

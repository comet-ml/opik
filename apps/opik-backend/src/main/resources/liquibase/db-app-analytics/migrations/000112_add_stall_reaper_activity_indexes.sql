--liquibase formatted sql
--changeset awkoy:000112_add_stall_reaper_activity_indexes
--comment: Add minmax skip indexes on experiments.created_at and experiment_items.created_at so the stalled-run reaper's recent-activity liveness probe prunes granules instead of scanning whole tables every cycle (OPIK-7459)

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.experiments ON CLUSTER '{cluster}'
    ADD INDEX IF NOT EXISTS idx_experiments_created_at created_at TYPE minmax GRANULARITY 1;

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.experiments ON CLUSTER '{cluster}'
    MATERIALIZE INDEX idx_experiments_created_at;

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.experiment_items ON CLUSTER '{cluster}'
    ADD INDEX IF NOT EXISTS idx_experiment_items_created_at created_at TYPE minmax GRANULARITY 1;

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.experiment_items ON CLUSTER '{cluster}'
    MATERIALIZE INDEX idx_experiment_items_created_at;

--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.experiment_items ON CLUSTER '{cluster}' DROP INDEX IF EXISTS idx_experiment_items_created_at;
--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.experiments ON CLUSTER '{cluster}' DROP INDEX IF EXISTS idx_experiments_created_at;

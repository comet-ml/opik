--liquibase formatted sql
--changeset admin:000048_add_studio_config_to_optimizations
--comment: Add studio_config column and new statuses to optimizations table for Optimization Studio feature

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.optimizations ON CLUSTER '{cluster}'
ADD COLUMN IF NOT EXISTS studio_config String;

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.optimizations ON CLUSTER '{cluster}'
MODIFY COLUMN status Enum8('running' = 0, 'completed' = 1, 'cancelled' = 2, 'initialized' = 3, 'error' = 4);

--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.optimizations ON CLUSTER '{cluster}' DROP COLUMN IF EXISTS studio_config;
--rollback empty

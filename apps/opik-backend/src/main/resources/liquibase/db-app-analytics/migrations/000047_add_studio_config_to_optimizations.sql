--liquibase formatted sql
--changeset admin:000046_add_studio_config_to_optimizations
--comment: Add studio_config column and new statuses to optimizations table for Optimization Studio feature

-- Add studio_config column for storing Optimization Studio configuration
ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.optimizations ON CLUSTER '{cluster}'
ADD COLUMN IF NOT EXISTS studio_config Nullable(String);

-- Add 'initialized' and 'error' statuses for better optimization lifecycle tracking
-- initialized: optimization created and queued
-- error: optimization failed during processing
ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.optimizations ON CLUSTER '{cluster}'
MODIFY COLUMN status ENUM('running' = 0, 'completed' = 1, 'cancelled' = 2, 'initialized' = 3, 'error' = 4);

--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.optimizations ON CLUSTER '{cluster}' DROP COLUMN IF EXISTS studio_config;
--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.optimizations ON CLUSTER '{cluster}' MODIFY COLUMN status ENUM('running' = 0, 'completed' = 1, 'cancelled' = 2);


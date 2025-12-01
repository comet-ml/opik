--liquibase formatted sql
--changeset admin:000046_add_studio_config_to_optimizations
--comment: Add studio_config column to optimizations table for Optimization Studio feature

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.optimizations ON CLUSTER '{cluster}'
ADD COLUMN IF NOT EXISTS studio_config String;


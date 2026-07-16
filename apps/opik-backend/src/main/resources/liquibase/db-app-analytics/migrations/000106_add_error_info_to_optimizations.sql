--liquibase formatted sql
--changeset admin:000106_add_error_info_to_optimizations
--comment: Add error_info column to optimizations table to persist the failure reason surfaced in Optimization Studio

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.optimizations ON CLUSTER '{cluster}'
ADD COLUMN IF NOT EXISTS error_info String DEFAULT '';

--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.optimizations ON CLUSTER '{cluster}' DROP COLUMN IF EXISTS error_info;

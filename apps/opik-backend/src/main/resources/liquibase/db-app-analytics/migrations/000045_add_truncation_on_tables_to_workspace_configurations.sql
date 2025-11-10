--liquibase formatted sql
--changeset andriidudar:000045_add_truncation_on_tables_to_workspace_configurations
--comment: Add boolean flag to enable/disable data truncation in table views for workspace configurations

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.workspace_configurations ON CLUSTER '{cluster}'
    ADD COLUMN IF NOT EXISTS truncation_on_tables Bool DEFAULT true;

--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.workspace_configurations ON CLUSTER '{cluster}' DROP COLUMN IF EXISTS truncation_on_tables;


--liquibase formatted sql
--changeset admin:000056_add_color_map_to_workspace_configurations
--comment: Add color_map column to workspace_configurations table for workspace-level color mappings

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.workspace_configurations ON CLUSTER '{cluster}'
    ADD COLUMN IF NOT EXISTS color_map String DEFAULT '';

--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.workspace_configurations ON CLUSTER '{cluster}' DROP COLUMN IF EXISTS color_map;


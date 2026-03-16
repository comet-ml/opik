--liquibase formatted sql
--changeset andriidudar:000056_drop_dashboard_name_uniqueness
--comment: Remove unique constraint on dashboard name to allow duplicate names across scopes

ALTER TABLE dashboards DROP INDEX dashboards_workspace_name_uk;

--rollback ALTER TABLE dashboards ADD CONSTRAINT dashboards_workspace_name_uk UNIQUE (workspace_id, name);

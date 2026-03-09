--liquibase formatted sql
--changeset andriidudar:add_dashboard_type_scope
--comment: Add type and scope columns to dashboards table

ALTER TABLE dashboards
    ADD COLUMN type VARCHAR(50) NOT NULL DEFAULT 'multi-project',
    ADD COLUMN scope VARCHAR(50) NOT NULL DEFAULT 'workspace';

--rollback ALTER TABLE dashboards DROP COLUMN type, DROP COLUMN scope;

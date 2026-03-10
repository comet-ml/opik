--liquibase formatted sql
--changeset andriidudar:add_dashboard_type_scope
--comment: Add type and scope columns to dashboards table

ALTER TABLE dashboards
    ADD COLUMN type ENUM('multi_project', 'experiments') NOT NULL DEFAULT 'multi_project',
    ADD COLUMN scope ENUM('workspace', 'insights') NOT NULL DEFAULT 'workspace';

--rollback ALTER TABLE dashboards DROP COLUMN type, DROP COLUMN scope;

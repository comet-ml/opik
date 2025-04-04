--liquibase formatted sql
--changeset BorisTkachenko:000013_add_projects_visibility

ALTER TABLE projects ADD COLUMN visibility ENUM('private', 'public') NOT NULL DEFAULT 'private';

--rollback ALTER TABLE projects DROP COLUMN visibility;

--liquibase formatted sql
--changeset BorisTkachenko:000013_add_projects_status

ALTER TABLE projects ADD COLUMN status ENUM('private', 'public') NOT NULL DEFAULT 'private';

--rollback ALTER TABLE projects DROP COLUMN status;

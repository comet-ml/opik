--liquibase formatted sql
--changeset BorisTkachenko:000013_add_projects_is_public

ALTER TABLE projects ADD COLUMN is_public BOOLEAN NOT NULL DEFAULT FALSE;

--rollback ALTER TABLE projects DROP COLUMN is_public;

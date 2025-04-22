--liquibase formatted sql
--changeset BorisTkachenko:000014_add_datasets_visibility

ALTER TABLE datasets ADD COLUMN visibility ENUM('private', 'public') NOT NULL DEFAULT 'private';

--rollback ALTER TABLE datasets DROP COLUMN visibility;

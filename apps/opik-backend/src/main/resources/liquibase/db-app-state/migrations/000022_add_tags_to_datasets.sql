--liquibase formatted sql
--changeset BorisTkachenko:000022_add_tags_to_datasets

ALTER TABLE datasets ADD COLUMN tags MEDIUMTEXT DEFAULT NULL;

--rollback ALTER TABLE datasets DROP COLUMN tags;

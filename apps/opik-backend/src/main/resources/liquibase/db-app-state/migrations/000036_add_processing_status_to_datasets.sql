--liquibase formatted sql
--changeset idoberko2:000036_add_status_to_datasets

ALTER TABLE datasets ADD COLUMN status ENUM('processing', 'completed', 'failed') DEFAULT NULL;

--rollback ALTER TABLE datasets DROP COLUMN status;


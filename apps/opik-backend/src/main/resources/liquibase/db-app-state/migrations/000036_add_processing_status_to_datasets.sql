--liquibase formatted sql
--changeset idoberko2:000036_add_status_to_datasets

ALTER TABLE datasets ADD COLUMN status ENUM('unknown', 'processing', 'completed', 'failed') DEFAULT 'unknown';

--rollback ALTER TABLE datasets DROP COLUMN status;


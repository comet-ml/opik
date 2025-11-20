--liquibase formatted sql
--changeset idoberko2:000036_add_processing_status_to_datasets

ALTER TABLE datasets ADD COLUMN processing_status ENUM('idle', 'processing', 'completed', 'failed') DEFAULT NULL;

--rollback ALTER TABLE datasets DROP COLUMN processing_status;


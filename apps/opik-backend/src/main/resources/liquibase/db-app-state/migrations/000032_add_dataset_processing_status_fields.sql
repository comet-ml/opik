--liquibase formatted sql
--changeset yariv:000032_add_dataset_processing_status_fields
--comment: Add processing status fields for async CSV processing

ALTER TABLE datasets ADD COLUMN csv_processing_status ENUM('ready', 'processing', 'failed') DEFAULT 'ready' NOT NULL;
ALTER TABLE datasets ADD COLUMN csv_processing_error TEXT NULL;
ALTER TABLE datasets ADD COLUMN csv_processed_at TIMESTAMP(6) NULL;
ALTER TABLE datasets ADD COLUMN csv_file_path VARCHAR(500) NULL COMMENT 'Path to CSV file in S3/MinIO for async processing';

--rollback ALTER TABLE datasets DROP COLUMN csv_file_path;
--rollback ALTER TABLE datasets DROP COLUMN csv_processed_at;
--rollback ALTER TABLE datasets DROP COLUMN csv_processing_error;
--rollback ALTER TABLE datasets DROP COLUMN csv_processing_status;


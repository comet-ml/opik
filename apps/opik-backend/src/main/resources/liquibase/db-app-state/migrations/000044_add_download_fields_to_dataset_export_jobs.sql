--liquibase formatted sql
--changeset thiagohora:000044_add_download_fields_to_dataset_export_jobs
--comment: Add download_url and viewed_at columns to dataset_export_jobs table

ALTER TABLE dataset_export_jobs ADD COLUMN download_url TEXT NULL AFTER file_path;
ALTER TABLE dataset_export_jobs ADD COLUMN viewed_at TIMESTAMP(6) NULL;

--rollback ALTER TABLE dataset_export_jobs DROP COLUMN viewed_at;
--rollback ALTER TABLE dataset_export_jobs DROP COLUMN download_url;



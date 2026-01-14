--liquibase formatted sql
--changeset thiagohora:000045_add_viewed_at_to_dataset_export_jobs
--comment: Add viewed_at column to dataset_export_jobs table

ALTER TABLE dataset_export_jobs ADD COLUMN viewed_at TIMESTAMP(6) NULL;

--rollback ALTER TABLE dataset_export_jobs DROP COLUMN viewed_at;

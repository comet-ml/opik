--liquibase formatted sql
--changeset thiagohora:000047_add_version_id_to_dataset_export_jobs
--comment: Add version_id column to dataset_export_jobs table to support version-specific exports

ALTER TABLE dataset_export_jobs ADD COLUMN version_id CHAR(36) NULL;

--rollback ALTER TABLE dataset_export_jobs DROP COLUMN version_id;

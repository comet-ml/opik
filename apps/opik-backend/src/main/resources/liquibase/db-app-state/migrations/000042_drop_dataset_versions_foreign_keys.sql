--liquibase formatted sql
--changeset idoberko2:000042_drop_dataset_versions_foreign_keys
--comment: Drop foreign key constraints from dataset_versions and dataset_version_tags tables

-- Drop foreign key from dataset_version_tags to dataset_versions
ALTER TABLE dataset_version_tags DROP FOREIGN KEY dataset_version_tags_version_id_fk;

-- Drop foreign key from dataset_version_tags to datasets
ALTER TABLE dataset_version_tags DROP FOREIGN KEY dataset_version_tags_dataset_id_fk;

-- Drop foreign key from dataset_versions to datasets
ALTER TABLE dataset_versions DROP FOREIGN KEY dataset_versions_dataset_id_fk;

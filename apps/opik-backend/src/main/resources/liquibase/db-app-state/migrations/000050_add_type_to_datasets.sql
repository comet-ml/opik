--liquibase formatted sql
--changeset JetoPistola:000050_add_type_to_datasets
--comment: Add type column to datasets table to distinguish datasets from evaluation suites

ALTER TABLE datasets ADD COLUMN type ENUM('dataset', 'evaluation_suite') NOT NULL DEFAULT 'dataset';

--rollback ALTER TABLE datasets DROP COLUMN type;

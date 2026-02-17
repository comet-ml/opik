--liquibase formatted sql
--changeset JetoPistola:000050_add_type_to_datasets

ALTER TABLE datasets ADD COLUMN type ENUM('dataset', 'evaluation_suite') NOT NULL DEFAULT 'dataset';

--rollback ALTER TABLE datasets DROP COLUMN type;

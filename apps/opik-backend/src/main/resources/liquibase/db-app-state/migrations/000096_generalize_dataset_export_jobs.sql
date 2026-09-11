--liquibase formatted sql
--changeset aliaksandrk:000096_generalize_dataset_export_jobs
--comment: Generalize dataset_export_jobs into export_jobs so experiment item exports reuse the same pipeline

RENAME TABLE dataset_export_jobs TO export_jobs;

-- VARCHAR rather than ENUM: a new export type must not require a schema migration.
ALTER TABLE export_jobs ADD COLUMN export_type VARCHAR(50) NOT NULL DEFAULT 'DATASET';

-- Everything the worker needs to produce the file, shaped per export type. Keeping it opaque is what stops
-- this table growing a nullable column every time a new surface becomes exportable.
ALTER TABLE export_jobs ADD COLUMN params JSON NULL;

-- Dedupe key: two requests for the same rows should share one job. Hashing the canonical params avoids
-- comparing JSON in SQL.
ALTER TABLE export_jobs ADD COLUMN params_hash CHAR(64) NULL;

-- Snapshot of what the job is exporting, for the progress panel. Taken at creation so the panel does not
-- need a join back to a resource that may since have been renamed or deleted.
ALTER TABLE export_jobs ADD COLUMN resource_name VARCHAR(255) NULL;

UPDATE export_jobs SET params = JSON_OBJECT('dataset_id', dataset_id) WHERE params IS NULL;

ALTER TABLE export_jobs DROP COLUMN dataset_id;

-- In-flight rows keep a NULL params_hash and so never match a dedupe lookup; the worst case is one redundant
-- export, and the cleanup job retires them within the configured TTL.
DROP INDEX idx_workspace_dataset_status ON export_jobs;

CREATE INDEX idx_workspace_type_hash_status
ON export_jobs(workspace_id, export_type, params_hash, status);

--rollback DROP INDEX idx_workspace_type_hash_status ON export_jobs;
--rollback ALTER TABLE export_jobs ADD COLUMN dataset_id CHAR(36) NULL;
--rollback UPDATE export_jobs SET dataset_id = JSON_UNQUOTE(JSON_EXTRACT(params, '$.dataset_id'));
--rollback ALTER TABLE export_jobs DROP COLUMN resource_name;
--rollback ALTER TABLE export_jobs DROP COLUMN params_hash;
--rollback ALTER TABLE export_jobs DROP COLUMN params;
--rollback ALTER TABLE export_jobs DROP COLUMN export_type;
--rollback CREATE INDEX idx_workspace_dataset_status ON export_jobs(workspace_id, dataset_id, status);
--rollback RENAME TABLE export_jobs TO dataset_export_jobs;

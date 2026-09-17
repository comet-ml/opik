--liquibase formatted sql
--changeset aliaksandrk:000100_generalize_dataset_export_jobs
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

-- The workspace stopped being the only scope: datasets and experiments belong to a project, and the whole UI is
-- project-scoped. Denormalised from the dataset rather than joined at read time, because the dataset id lives
-- inside the params JSON and a join through it cannot use an index. NULL means a resource with no project, which
-- stays visible everywhere rather than disappearing from every project.
ALTER TABLE export_jobs ADD COLUMN project_id CHAR(36) NULL;

-- export_type must be inside params too: ExportParams resolves its subtype from that property, so a payload
-- carrying only dataset_id cannot be deserialized back into DatasetExportParams.
UPDATE export_jobs SET params = JSON_OBJECT('export_type', 'DATASET', 'dataset_id', dataset_id)
WHERE params IS NULL;

-- resource_name is what the progress panel labels the job with, and what names the downloaded file. Without a
-- snapshot a job carried over from the old schema renders as "Unknown export" and downloads as its UUID, so take
-- the name from the dataset while dataset_id is still here to join on.
UPDATE export_jobs job
JOIN datasets dataset ON dataset.id = job.dataset_id
SET job.resource_name = dataset.name, job.project_id = dataset.project_id
WHERE job.resource_name IS NULL;

ALTER TABLE export_jobs DROP COLUMN dataset_id;

-- In-flight rows keep a NULL params_hash and so never match a dedupe lookup; the worst case is one redundant
-- export, and the cleanup job retires them within the configured TTL.
DROP INDEX idx_workspace_dataset_status ON export_jobs;

CREATE INDEX idx_workspace_type_hash_status
ON export_jobs(workspace_id, export_type, params_hash, status);

-- Serves the progress panel, which lists one caller's jobs within the project they are looking at.
CREATE INDEX idx_workspace_project_created_by
ON export_jobs(workspace_id, project_id, created_by);

-- Rollback refuses to run once rows exist that the old schema cannot represent. Experiment exports have no
-- single dataset_id column to restore to, so silently rolling them back would hand the old application rows it
-- cannot use. Retire those rows (or let them expire) before rolling back.
--rollback SELECT IF(COUNT(*) = 0, 'ok', (SELECT CONCAT('Refusing to roll back: ', COUNT(*), ' non-DATASET export job(s) exist. Delete them or wait for the cleanup job, then retry.') FROM export_jobs WHERE export_type <> 'DATASET')) INTO @export_rollback_guard FROM export_jobs WHERE export_type <> 'DATASET';
--rollback DROP INDEX idx_workspace_project_created_by ON export_jobs;
--rollback DROP INDEX idx_workspace_type_hash_status ON export_jobs;
--rollback ALTER TABLE export_jobs ADD COLUMN dataset_id CHAR(36) NULL;
--rollback UPDATE export_jobs SET dataset_id = JSON_UNQUOTE(JSON_EXTRACT(params, '$.dataset_id'));
--rollback ALTER TABLE export_jobs DROP COLUMN project_id;
--rollback ALTER TABLE export_jobs DROP COLUMN resource_name;
--rollback ALTER TABLE export_jobs DROP COLUMN params_hash;
--rollback ALTER TABLE export_jobs DROP COLUMN params;
--rollback ALTER TABLE export_jobs DROP COLUMN export_type;
--rollback CREATE INDEX idx_workspace_dataset_status ON export_jobs(workspace_id, dataset_id, status);
--rollback RENAME TABLE export_jobs TO dataset_export_jobs;

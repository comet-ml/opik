--liquibase formatted sql
--changeset thiagohora:000045_add_viewed_at_to_dataset_export_jobs
--comment: Add viewed_at column to dataset_export_jobs table

ALTER TABLE dataset_export_jobs ADD COLUMN viewed_at TIMESTAMP(6) NULL;


-- Drop old expires_at index (will be replaced with composite index)
DROP INDEX idx_expires_at ON dataset_export_jobs;

-- Add optimized composite indexes for cleanup queries

-- Index for cleanup query: expired completed jobs (status + expires_at for efficient range scan)
CREATE INDEX idx_status_expires_at 
ON dataset_export_jobs(status, expires_at);

-- Index for cleanup query: viewed failed jobs (status + viewed_at for efficient NULL and range scans)
CREATE INDEX idx_status_viewed_at 
ON dataset_export_jobs(status, viewed_at);

--rollback ALTER TABLE dataset_export_jobs DROP COLUMN viewed_at;
--rollback DROP INDEX idx_status_viewed_at ON dataset_export_jobs;
--rollback DROP INDEX idx_status_expires_at ON dataset_export_jobs;
--rollback CREATE INDEX idx_expires_at ON dataset_export_jobs(expires_at);

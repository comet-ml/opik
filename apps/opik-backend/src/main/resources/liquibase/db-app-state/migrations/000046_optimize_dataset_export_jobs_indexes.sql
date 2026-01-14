--liquibase formatted sql
--changeset thiagohora:000045_optimize_dataset_export_jobs_indexes
--comment: Optimize indexes for dataset export job cleanup queries

-- Drop old expires_at index (will be replaced with composite index)
DROP INDEX idx_expires_at ON dataset_export_jobs;

-- Add optimized composite indexes for cleanup queries

-- Index for cleanup query: expired completed jobs (status + expires_at for efficient range scan)
CREATE INDEX idx_status_expires_at 
ON dataset_export_jobs(status, expires_at);

-- Index for cleanup query: viewed failed jobs (status + viewed_at for efficient NULL and range scans)
CREATE INDEX idx_status_viewed_at 
ON dataset_export_jobs(status, viewed_at);

--rollback DROP INDEX idx_status_viewed_at ON dataset_export_jobs;
--rollback DROP INDEX idx_status_expires_at ON dataset_export_jobs;
--rollback CREATE INDEX idx_expires_at ON dataset_export_jobs(expires_at);


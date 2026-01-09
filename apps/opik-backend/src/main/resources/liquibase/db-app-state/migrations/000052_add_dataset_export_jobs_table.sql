--liquibase formatted sql
--changeset andrescrz:000052_add_dataset_export_jobs_table

CREATE TABLE IF NOT EXISTS dataset_export_jobs (
    id CHAR(36) PRIMARY KEY,
    workspace_id VARCHAR(150) NOT NULL,
    dataset_id CHAR(36) NOT NULL,
    status ENUM('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED') NOT NULL,
    file_path VARCHAR(500),
    error_message VARCHAR(255),
    created_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP(6),
    last_updated_at TIMESTAMP(6),
    expires_at TIMESTAMP(6),
    created_by VARCHAR(100),
    INDEX idx_workspace_status (workspace_id, status),
    INDEX idx_expires_at (expires_at)
);

--rollback DROP TABLE IF EXISTS dataset_export_jobs;


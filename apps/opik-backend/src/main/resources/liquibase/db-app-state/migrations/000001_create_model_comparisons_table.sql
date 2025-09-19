--liquibase formatted sql
--changeset author:000001_create_model_comparisons_table
--comment: Create model_comparisons table for storing model comparison configurations and results

CREATE TABLE model_comparisons (
    id VARCHAR(36) PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    model_ids JSON NOT NULL,
    dataset_names JSON NOT NULL,
    filters JSON,
    results JSON,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    last_updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    created_by VARCHAR(255),
    last_updated_by VARCHAR(255)
);

-- Index for searching by name and description
CREATE INDEX idx_model_comparisons_name ON model_comparisons(name);
CREATE INDEX idx_model_comparisons_description ON model_comparisons(description);

-- Index for created_at for sorting
CREATE INDEX idx_model_comparisons_created_at ON model_comparisons(created_at);

-- Index for JSON fields (MySQL 8.0+)
CREATE INDEX idx_model_comparisons_model_ids ON model_comparisons((CAST(model_ids AS CHAR(255) ARRAY)));
CREATE INDEX idx_model_comparisons_dataset_names ON model_comparisons((CAST(dataset_names AS CHAR(255) ARRAY)));

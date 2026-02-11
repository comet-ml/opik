--liquibase formatted sql
--changeset danieldimenshtein:000049_add_dataset_evaluators_table
--comment: Create dataset_evaluators table for storing dataset-level SDK metric configurations

CREATE TABLE IF NOT EXISTS dataset_evaluators (
    id CHAR(36) NOT NULL,
    workspace_id VARCHAR(150) NOT NULL,
    dataset_id CHAR(36) NOT NULL,
    name VARCHAR(255) NOT NULL,
    type ENUM('llm_judge', 'code_metric') NOT NULL,
    config JSON NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by VARCHAR(100) NOT NULL DEFAULT 'admin',
    last_updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    last_updated_by VARCHAR(100) NOT NULL DEFAULT 'admin',
    CONSTRAINT `dataset_evaluators_pk` PRIMARY KEY (id),
    INDEX `dataset_evaluators_workspace_dataset_idx` (workspace_id, dataset_id)
);

--rollback DROP TABLE IF EXISTS dataset_evaluators;

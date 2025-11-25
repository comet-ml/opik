--liquibase formatted sql
--changeset idoberko2:000036_add_dataset_versions_tables
--comment: Create dataset_versions and dataset_version_tags tables for Git-like versioning

CREATE TABLE IF NOT EXISTS dataset_versions (
    id CHAR(36) NOT NULL,
    dataset_id CHAR(36) NOT NULL,
    version_hash VARCHAR(64) NOT NULL,
    workspace_id VARCHAR(150) NOT NULL,
    items_total INT DEFAULT 0,
    items_added INT DEFAULT 0,
    items_modified INT DEFAULT 0,
    items_deleted INT DEFAULT 0,
    change_description TEXT,
    metadata JSON,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by VARCHAR(100) NOT NULL DEFAULT 'admin',
    last_updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    last_updated_by VARCHAR(100) NOT NULL DEFAULT 'admin',
    CONSTRAINT `dataset_versions_pk` PRIMARY KEY (id),
    CONSTRAINT `dataset_versions_dataset_hash_uk` UNIQUE (workspace_id, dataset_id, version_hash),
    CONSTRAINT `dataset_versions_dataset_id_fk` FOREIGN KEY (dataset_id) REFERENCES datasets(id)
);

CREATE TABLE IF NOT EXISTS dataset_version_tags (
    workspace_id VARCHAR(150) NOT NULL,
    dataset_id CHAR(36) NOT NULL,
    tag VARCHAR(100) NOT NULL,
    version_id CHAR(36) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by VARCHAR(100) NOT NULL DEFAULT 'admin',
    last_updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    last_updated_by VARCHAR(100) NOT NULL DEFAULT 'admin',
    CONSTRAINT `dataset_version_tags_pk` PRIMARY KEY (workspace_id, dataset_id, tag),
    CONSTRAINT `dataset_version_tags_dataset_id_fk` FOREIGN KEY (dataset_id) REFERENCES datasets(id),
    CONSTRAINT `dataset_version_tags_version_id_fk` FOREIGN KEY (version_id) REFERENCES dataset_versions(id)
);

--rollback DROP TABLE IF EXISTS dataset_version_tags;
--rollback DROP TABLE IF EXISTS dataset_versions;

--liquibase formatted sql
--changeset TagsManagement:000022_create_tags_table

CREATE TABLE tags (
    id CHAR(36) NOT NULL PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    description TEXT,
    workspace_id VARCHAR(255) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_tags_workspace_name (workspace_id, name),
    INDEX idx_tags_workspace_id (workspace_id),
    INDEX idx_tags_name (name)
);

--rollback DROP TABLE tags;
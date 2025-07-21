--liquibase formatted sql
--changeset thiagohora:000024_add_workspace_configurations_table

CREATE TABLE workspace_configurations (
    workspace_id VARCHAR(150) NOT NULL,
    timeout_mark_thread_as_inactive INT NULL, -- timeout in seconds, null means no timeout
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by VARCHAR(100) NOT NULL DEFAULT 'admin',
    last_updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    last_updated_by VARCHAR(100) NOT NULL DEFAULT 'admin',
    CONSTRAINT `workspace_configurations_pk` PRIMARY KEY (workspace_id)
);

--rollback DROP TABLE IF EXISTS workspace_configurations;

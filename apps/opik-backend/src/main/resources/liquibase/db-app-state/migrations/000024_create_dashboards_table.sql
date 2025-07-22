--liquibase formatted sql
--changeset yariv:000024_create_dashboards_table

CREATE TABLE dashboards (
    id CHAR(36) NOT NULL,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    layout JSON NOT NULL,
    filters JSON NOT NULL DEFAULT (JSON_OBJECT()),
    refresh_interval INTEGER NOT NULL DEFAULT 30,
    workspace_id VARCHAR(150) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by VARCHAR(100) NOT NULL,
    last_updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    last_updated_by VARCHAR(100) NOT NULL,
    CONSTRAINT `dashboards_pk` PRIMARY KEY (id),
    INDEX `dashboards_workspace_id_idx` (workspace_id),
    INDEX `dashboards_created_at_idx` (created_at),
    INDEX `dashboards_name_idx` (name)
);

-- Add unique constraint for dashboard name per workspace
ALTER TABLE dashboards ADD CONSTRAINT `dashboards_workspace_id_name_uk` UNIQUE (workspace_id, name); 
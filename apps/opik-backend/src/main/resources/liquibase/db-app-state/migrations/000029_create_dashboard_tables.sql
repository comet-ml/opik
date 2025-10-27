--liquibase formatted sql
--changeset yariv:000029_create_dashboard_tables
--comment: Create dashboard management tables for custom dashboards and charts

-- Dashboards table
CREATE TABLE dashboards (
    id CHAR(36) PRIMARY KEY,
    workspace_id VARCHAR(255) NOT NULL,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    dashboard_type ENUM('prebuilt', 'custom') NOT NULL,
    is_default BOOLEAN DEFAULT FALSE,
    created_by VARCHAR(255),
    last_updated_by VARCHAR(255),
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    last_updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    INDEX idx_workspace (workspace_id),
    INDEX idx_type (dashboard_type),
    INDEX idx_created_at (created_at)
);

-- Dashboard-Project associations (for prebuilt dashboards)
CREATE TABLE dashboard_projects (
    id CHAR(36) PRIMARY KEY,
    dashboard_id CHAR(36) NOT NULL,
    project_id CHAR(36) NOT NULL,
    is_default BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    FOREIGN KEY (dashboard_id) REFERENCES dashboards(id) ON DELETE CASCADE,
    UNIQUE KEY unique_dashboard_project (dashboard_id, project_id),
    INDEX idx_project (project_id),
    INDEX idx_dashboard (dashboard_id)
);

-- Charts table
CREATE TABLE dashboard_charts (
    id CHAR(36) PRIMARY KEY,
    dashboard_id CHAR(36) NOT NULL,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    chart_type ENUM('line', 'bar') NOT NULL DEFAULT 'line',
    position_x INT DEFAULT 0,
    position_y INT DEFAULT 0,
    width INT DEFAULT 1,
    height INT DEFAULT 1,
    created_by VARCHAR(255),
    last_updated_by VARCHAR(255),
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    last_updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    FOREIGN KEY (dashboard_id) REFERENCES dashboards(id) ON DELETE CASCADE,
    INDEX idx_dashboard (dashboard_id),
    INDEX idx_created_at (created_at)
);

-- Chart data series configuration
CREATE TABLE chart_data_series (
    id CHAR(36) PRIMARY KEY,
    chart_id CHAR(36) NOT NULL,
    project_id CHAR(36),
    metric_type VARCHAR(50) NOT NULL,
    name VARCHAR(255),
    filters JSON,
    color VARCHAR(7),
    series_order INT DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    FOREIGN KEY (chart_id) REFERENCES dashboard_charts(id) ON DELETE CASCADE,
    INDEX idx_chart (chart_id),
    INDEX idx_series_order (chart_id, series_order)
);

-- Chart grouping configuration
CREATE TABLE chart_grouping (
    id CHAR(36) PRIMARY KEY,
    chart_id CHAR(36) NOT NULL UNIQUE,
    group_by_field VARCHAR(100),
    group_by_type ENUM('automatic', 'manual') DEFAULT 'automatic',
    limit_top_n INT DEFAULT 5,
    FOREIGN KEY (chart_id) REFERENCES dashboard_charts(id) ON DELETE CASCADE
);

-- Saved filters
CREATE TABLE saved_filters (
    id CHAR(36) PRIMARY KEY,
    workspace_id VARCHAR(255) NOT NULL,
    project_id CHAR(36) NOT NULL,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    filters JSON NOT NULL,
    filter_type ENUM('trace', 'thread') NOT NULL,
    created_by VARCHAR(255),
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    last_updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    INDEX idx_workspace_project (workspace_id, project_id),
    INDEX idx_created_at (created_at)
);

--rollback DROP TABLE saved_filters;
--rollback DROP TABLE chart_grouping;
--rollback DROP TABLE chart_data_series;
--rollback DROP TABLE dashboard_charts;
--rollback DROP TABLE dashboard_projects;
--rollback DROP TABLE dashboards;




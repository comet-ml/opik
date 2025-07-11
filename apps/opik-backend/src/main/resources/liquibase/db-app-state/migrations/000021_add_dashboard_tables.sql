--liquibase formatted sql
--changeset yariv:add_dashboard_tables

CREATE TABLE dashboard_templates (
    id CHAR(36) NOT NULL,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    workspace_id VARCHAR(150) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by VARCHAR(100) NOT NULL DEFAULT 'admin',
    last_updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    last_updated_by VARCHAR(100) NOT NULL DEFAULT 'admin',
    CONSTRAINT `dashboard_templates_pk` PRIMARY KEY (id),
    CONSTRAINT `dashboard_templates_workspace_id_name_uk` UNIQUE (workspace_id, name),
    INDEX `dashboard_templates_workspace_id` (workspace_id)
);

CREATE TABLE dashboard_sections (
    id CHAR(36) NOT NULL,
    dashboard_id CHAR(36) NOT NULL,
    title VARCHAR(255) NOT NULL,
    position_order INT NOT NULL DEFAULT 0,
    is_expanded BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by VARCHAR(100) NOT NULL DEFAULT 'admin',
    last_updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    last_updated_by VARCHAR(100) NOT NULL DEFAULT 'admin',
    CONSTRAINT `dashboard_sections_pk` PRIMARY KEY (id),
    CONSTRAINT `dashboard_sections_dashboard_id_fk` FOREIGN KEY (dashboard_id) REFERENCES dashboard_templates(id) ON DELETE CASCADE,
    INDEX `dashboard_sections_dashboard_id` (dashboard_id)
);

CREATE TABLE dashboard_panels (
    id CHAR(36) NOT NULL,
    section_id CHAR(36) NULL,
    name VARCHAR(255) NOT NULL,
    type ENUM('python', 'chart', 'text', 'metric', 'html') NOT NULL,
    configuration JSON NOT NULL,
    layout JSON NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by VARCHAR(100) NOT NULL DEFAULT 'admin',
    last_updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    last_updated_by VARCHAR(100) NOT NULL DEFAULT 'admin',
    CONSTRAINT `dashboard_panels_pk` PRIMARY KEY (id),
    CONSTRAINT `dashboard_panels_section_id_fk` FOREIGN KEY (section_id) REFERENCES dashboard_sections(id) ON DELETE SET NULL,
    INDEX `dashboard_panels_section_id` (section_id)
);

CREATE TABLE experiment_dashboards (
    experiment_id CHAR(36) NOT NULL,
    dashboard_id CHAR(36) NOT NULL,
    workspace_id VARCHAR(150) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by VARCHAR(100) NOT NULL DEFAULT 'admin',
    last_updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    last_updated_by VARCHAR(100) NOT NULL DEFAULT 'admin',
    CONSTRAINT `experiment_dashboards_pk` PRIMARY KEY (experiment_id),
    CONSTRAINT `experiment_dashboards_dashboard_id_fk` FOREIGN KEY (dashboard_id) REFERENCES dashboard_templates(id) ON DELETE CASCADE,
    INDEX `experiment_dashboards_workspace_id_experiment_id` (workspace_id, experiment_id)
);

--rollback DROP TABLE IF EXISTS experiment_dashboards;
--rollback DROP TABLE IF EXISTS dashboard_panels;
--rollback DROP TABLE IF EXISTS dashboard_sections;
--rollback DROP TABLE IF EXISTS dashboard_templates; 

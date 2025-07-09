--liquibase formatted sql
--changeset yariv:add_reusable_panel_templates

-- Create reusable panel templates table
CREATE TABLE reusable_panel_templates (
    id CHAR(36) NOT NULL,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    type ENUM('python', 'chart', 'text', 'metric', 'html') NOT NULL,
    configuration JSON NOT NULL,
    default_layout JSON NOT NULL,
    workspace_id VARCHAR(150) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by VARCHAR(100) NOT NULL DEFAULT 'admin',
    last_updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    last_updated_by VARCHAR(100) NOT NULL DEFAULT 'admin',
    CONSTRAINT `reusable_panel_templates_pk` PRIMARY KEY (id),
    CONSTRAINT `reusable_panel_templates_workspace_id_name_uk` UNIQUE (workspace_id, name),
    INDEX `reusable_panel_templates_workspace_id` (workspace_id),
    INDEX `reusable_panel_templates_type` (type)
);

-- Add optional reference to reusable panel template in dashboard_panels
ALTER TABLE dashboard_panels 
ADD COLUMN template_id CHAR(36) NULL,
ADD CONSTRAINT `dashboard_panels_template_id_fk` 
    FOREIGN KEY (template_id) REFERENCES reusable_panel_templates(id) ON DELETE SET NULL,
ADD INDEX `dashboard_panels_template_id` (template_id);

--rollback ALTER TABLE dashboard_panels DROP FOREIGN KEY dashboard_panels_template_id_fk;
--rollback ALTER TABLE dashboard_panels DROP INDEX dashboard_panels_template_id;
--rollback ALTER TABLE dashboard_panels DROP COLUMN template_id;
--rollback DROP TABLE IF EXISTS reusable_panel_templates; 
--liquibase formatted sql
--changeset dashboard_rename_and_templates:1

-- Step 1: Drop all foreign key constraints before renaming tables
ALTER TABLE dashboard_panels DROP FOREIGN KEY dashboard_panels_section_id_fk;
ALTER TABLE dashboard_sections DROP FOREIGN KEY dashboard_sections_dashboard_id_fk;
ALTER TABLE experiment_dashboards DROP FOREIGN KEY experiment_dashboards_dashboard_id_fk;

-- Step 2: Drop foreign key constraint from panel templates if it exists
ALTER TABLE dashboard_panels DROP FOREIGN KEY dashboard_panels_template_id_fk;

-- Step 3: Rename existing dashboard tables to be experiment-specific
RENAME TABLE dashboard_templates TO experiment_dashboard_templates;
RENAME TABLE dashboard_sections TO experiment_dashboard_sections;
RENAME TABLE dashboard_panels TO experiment_dashboard_panels;

-- Step 4: Ensure section_id column is nullable (it should be, but make sure)
ALTER TABLE experiment_dashboard_panels MODIFY COLUMN section_id CHAR(36) NULL;

-- Step 5: Recreate foreign key constraints with new table references
ALTER TABLE experiment_dashboard_sections 
ADD CONSTRAINT experiment_dashboard_sections_dashboard_id_fk 
FOREIGN KEY (dashboard_id) REFERENCES experiment_dashboard_templates(id) ON DELETE CASCADE;

ALTER TABLE experiment_dashboard_panels 
ADD CONSTRAINT experiment_dashboard_panels_section_id_fk 
FOREIGN KEY (section_id) REFERENCES experiment_dashboard_sections(id) ON DELETE SET NULL;

ALTER TABLE experiment_dashboards 
ADD CONSTRAINT experiment_dashboards_dashboard_id_fk 
FOREIGN KEY (dashboard_id) REFERENCES experiment_dashboard_templates(id) ON DELETE CASCADE;

-- Step 6: Add panel template foreign key to reference the renamed table
ALTER TABLE experiment_dashboard_panels 
ADD CONSTRAINT experiment_dashboard_panels_template_id_fk 
FOREIGN KEY (template_id) REFERENCES reusable_panel_templates(id) ON DELETE SET NULL;

-- Create new dashboard templates table for reusable dashboard configurations
CREATE TABLE dashboard_templates (
    id                  CHAR(36) NOT NULL PRIMARY KEY,
    name               VARCHAR(255) NOT NULL,
    description        TEXT,
    configuration      JSON NOT NULL COMMENT 'Complete dashboard structure with sections and panels',
    workspace_id       VARCHAR(150) NOT NULL,
    created_at         TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP(6) NOT NULL,
    created_by         VARCHAR(320) NOT NULL,
    last_updated_at    TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) NOT NULL,
    last_updated_by    VARCHAR(320) NOT NULL,
    
    UNIQUE KEY unique_dashboard_template_name_workspace (name, workspace_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE INDEX idx_dashboard_templates_workspace_id ON dashboard_templates(workspace_id);
CREATE INDEX idx_dashboard_templates_name ON dashboard_templates(name);
CREATE INDEX idx_dashboard_templates_created_at ON dashboard_templates(created_at);

--rollback DROP TABLE IF EXISTS dashboard_templates;
--rollback ALTER TABLE experiment_dashboard_panels DROP FOREIGN KEY experiment_dashboard_panels_template_id_fk;
--rollback ALTER TABLE experiment_dashboards DROP FOREIGN KEY experiment_dashboards_dashboard_id_fk;
--rollback ALTER TABLE experiment_dashboard_panels DROP FOREIGN KEY experiment_dashboard_panels_section_id_fk;
--rollback ALTER TABLE experiment_dashboard_sections DROP FOREIGN KEY experiment_dashboard_sections_dashboard_id_fk;
--rollback RENAME TABLE experiment_dashboard_panels TO dashboard_panels;
--rollback RENAME TABLE experiment_dashboard_sections TO dashboard_sections;
--rollback RENAME TABLE experiment_dashboard_templates TO dashboard_templates;
--rollback ALTER TABLE dashboard_sections ADD CONSTRAINT dashboard_sections_dashboard_id_fk FOREIGN KEY (dashboard_id) REFERENCES dashboard_templates(id) ON DELETE CASCADE;
--rollback ALTER TABLE dashboard_panels ADD CONSTRAINT dashboard_panels_section_id_fk FOREIGN KEY (section_id) REFERENCES dashboard_sections(id) ON DELETE SET NULL;
--rollback ALTER TABLE experiment_dashboards ADD CONSTRAINT experiment_dashboards_dashboard_id_fk FOREIGN KEY (dashboard_id) REFERENCES dashboard_templates(id) ON DELETE CASCADE;
--rollback ALTER TABLE dashboard_panels ADD CONSTRAINT dashboard_panels_template_id_fk FOREIGN KEY (template_id) REFERENCES reusable_panel_templates(id) ON DELETE SET NULL; 
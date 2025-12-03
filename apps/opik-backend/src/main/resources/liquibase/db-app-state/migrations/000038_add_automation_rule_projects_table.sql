--liquibase formatted sql
--changeset danield:000038_add_automation_rule_projects_table
--comment: Add automation_rule_projects junction table to support multiple projects per automation rule

-- Create junction table for many-to-many relationship between automation rules and projects
CREATE TABLE IF NOT EXISTS automation_rule_projects (
    rule_id CHAR(36) NOT NULL,
    project_id CHAR(36) NOT NULL,
    workspace_id VARCHAR(150) NOT NULL,
    
    CONSTRAINT `automation_rule_projects_pk` PRIMARY KEY (rule_id, project_id),
    CONSTRAINT `automation_rule_projects_rule_fk` FOREIGN KEY (rule_id) 
        REFERENCES automation_rules(id) ON DELETE CASCADE,
    CONSTRAINT `automation_rule_projects_project_fk` FOREIGN KEY (project_id) 
        REFERENCES projects(id) ON DELETE CASCADE,
    INDEX `automation_rule_projects_workspace_idx` (workspace_id),
    INDEX `automation_rule_projects_project_idx` (project_id),
    INDEX `automation_rule_projects_rule_idx` (rule_id)
);

-- Migrate existing data from automation_rules.project_id to junction table
INSERT INTO automation_rule_projects (rule_id, project_id, workspace_id)
SELECT id, project_id, workspace_id 
FROM automation_rules;

-- Drop old index that included project_id
DROP INDEX `automation_rules_idx` ON automation_rules;

-- Drop project_id column from automation_rules
ALTER TABLE automation_rules DROP COLUMN project_id;

-- Create new index without project_id
CREATE INDEX `automation_rules_workspace_idx` ON automation_rules(workspace_id, id);

--rollback DROP TABLE IF EXISTS automation_rule_projects;
--rollback ALTER TABLE automation_rules ADD COLUMN project_id CHAR(36) NOT NULL AFTER id;
--rollback CREATE INDEX `automation_rules_idx` ON automation_rules(workspace_id, project_id, id);
--rollback DROP INDEX `automation_rules_workspace_idx` ON automation_rules;


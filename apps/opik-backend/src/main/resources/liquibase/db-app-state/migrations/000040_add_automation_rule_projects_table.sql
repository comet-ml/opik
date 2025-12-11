--liquibase formatted sql
--changeset JetoPistola:000040_add_automation_rule_projects_table
--comment: Add automation_rule_projects junction table for multi-project support (dual-field backwards compatible architecture)

-- Create junction table for many-to-many relationship between automation rules and projects
-- This table enables assigning a single rule to multiple projects
-- The existing project_id column in automation_rules is kept for backwards compatibility
CREATE TABLE IF NOT EXISTS automation_rule_projects (
    rule_id CHAR(36) NOT NULL,
    project_id CHAR(36) NOT NULL,
    workspace_id VARCHAR(150) NOT NULL,
    
    CONSTRAINT `automation_rule_projects_pk` PRIMARY KEY (rule_id, project_id),
    INDEX `automation_rule_projects_workspace_idx` (workspace_id),
    INDEX `automation_rule_projects_project_idx` (project_id),
    INDEX `automation_rule_projects_rule_idx` (rule_id)
);

-- Note: No data migration performed - using lazy migration strategy
-- Existing rules will continue using project_id field (legacy)
-- New rules will use both project_id (primary) and junction table (multi-project support)
-- When a rule is updated, it will be migrated from project_id to junction table automatically

--rollback DROP TABLE IF EXISTS automation_rule_projects;


--liquibase formatted sql
--changeset JetoPistola:000041_add_automation_rule_projects_table
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
    INDEX `automation_rule_projects_project_idx` (project_id)
);

-- Make project_id nullable since we're no longer writing to it (single source of truth is junction table)
-- Existing legacy data will keep their project_id values, new rules will have NULL
ALTER TABLE automation_rules MODIFY COLUMN project_id CHAR(36) NULL DEFAULT NULL;

-- Note: No data migration performed - using lazy migration strategy
-- Existing rules will continue having their project_id field populated (legacy data)
-- New rules will have project_id = NULL and use junction table exclusively
-- The Service layer will populate projectId from projectIds for backward compatibility

-- Rollback: This is a one-way migration. Rolling back would:
-- 1. Fail if any rules have project_id = NULL (new rules created after migration)
-- 2. Cause data loss by dropping automation_rule_projects table
-- Therefore, rollback is explicitly marked as empty (non-rollbackable)
--rollback empty

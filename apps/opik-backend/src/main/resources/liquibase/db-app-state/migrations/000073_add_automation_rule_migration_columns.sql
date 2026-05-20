--liquibase formatted sql
--changeset DanielCreao:add_automation_rule_migration_columns

ALTER TABLE workspaces
    ADD COLUMN automation_rule_project_migration_skipped_at TIMESTAMP(6) DEFAULT NULL,
    ADD COLUMN automation_rule_project_migration_skip_reason VARCHAR(100) DEFAULT NULL;

CREATE INDEX workspaces_ar_migration_skipped_idx ON workspaces (automation_rule_project_migration_skipped_at);

--rollback DROP INDEX workspaces_ar_migration_skipped_idx ON workspaces;
--rollback ALTER TABLE workspaces DROP COLUMN automation_rule_project_migration_skip_reason;
--rollback ALTER TABLE workspaces DROP COLUMN automation_rule_project_migration_skipped_at;

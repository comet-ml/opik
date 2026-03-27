--liquibase formatted sql
--changeset andrescrz:000061_add_workspace_rule_project_index
--comment: Add covering index for workspace version multi-project rule check (OPIK-5171)

CREATE INDEX automation_rule_projects_workspace_rule_project_idx ON automation_rule_projects(workspace_id, rule_id, project_id);

--rollback DROP INDEX automation_rule_projects_workspace_rule_project_idx ON automation_rule_projects;

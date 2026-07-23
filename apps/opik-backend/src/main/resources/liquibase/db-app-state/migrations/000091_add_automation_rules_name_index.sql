--liquibase formatted sql
--changeset yaroslavb:000091_add_automation_rules_name_index
--comment: Add index to support prefix lookup of automation rule names for auto-suffixing (OPIK-7371)

CREATE INDEX automation_rules_workspace_name_idx ON automation_rules(workspace_id, name);

--rollback DROP INDEX automation_rules_workspace_name_idx ON automation_rules;

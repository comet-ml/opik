--liquibase formatted sql
--changeset petrot:000082_alter_agent_insights_issues
--comment: Evolve agent_insights_issues: rename query->traces_query, add suggested_fix, cause, swap unique key from name-based to id-based.

ALTER TABLE agent_insights_issues
    CHANGE COLUMN `query` traces_query TEXT NULL,
    ADD COLUMN cause         TEXT NULL AFTER description,
    ADD COLUMN suggested_fix TEXT NULL AFTER cause,
    DROP INDEX agent_insights_issues_workspace_project_name_uk,
    ADD UNIQUE KEY agent_insights_issues_workspace_project_id_uk (workspace_id, project_id, id);

--rollback ALTER TABLE agent_insights_issues DROP INDEX agent_insights_issues_workspace_project_id_uk, ADD UNIQUE KEY agent_insights_issues_workspace_project_name_uk (workspace_id, project_id, name), DROP COLUMN suggested_fix, DROP COLUMN cause, CHANGE COLUMN traces_query `query` TEXT NULL;

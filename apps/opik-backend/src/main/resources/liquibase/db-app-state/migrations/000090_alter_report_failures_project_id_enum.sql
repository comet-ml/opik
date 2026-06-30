--liquibase formatted sql
--changeset aadereiko:000090_alter_report_failures_project_id_enum
--comment: Rename report_failures.entity_id -> project_id, make type an enum, and refresh the lookup index.
--comment: Forward migration because 000088 is already deployed (dev) — its checksum is locked, same as 000089.

-- Drop the index first so it no longer depends on the column being renamed.
ALTER TABLE report_failures DROP INDEX report_failures_lookup_idx;

ALTER TABLE report_failures CHANGE COLUMN entity_id project_id CHAR(36) NOT NULL;

ALTER TABLE report_failures MODIFY COLUMN type ENUM('agent_insights') NOT NULL;

ALTER TABLE report_failures ADD INDEX report_failures_lookup_idx (workspace_id, project_id, type, created_at);

--rollback ALTER TABLE report_failures DROP INDEX report_failures_lookup_idx;
--rollback ALTER TABLE report_failures MODIFY COLUMN type VARCHAR(100) NOT NULL;
--rollback ALTER TABLE report_failures CHANGE COLUMN project_id entity_id CHAR(36) NOT NULL;
--rollback ALTER TABLE report_failures ADD INDEX report_failures_lookup_idx (workspace_id, type, entity_id, created_at);

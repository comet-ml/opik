--liquibase formatted sql
--changeset thiagohora:000060_add_project_id_to_alerts

ALTER TABLE alerts ADD COLUMN project_id CHAR(36) NULL;

-- Index for project-scoped alert queries
CREATE INDEX idx_alerts_project_id ON alerts(workspace_id, project_id);

--rollback ALTER TABLE alerts DROP COLUMN project_id;

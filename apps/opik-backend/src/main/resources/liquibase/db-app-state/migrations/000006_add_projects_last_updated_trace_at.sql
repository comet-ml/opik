--liquibase formatted sql
--changeset idoberko2:add_projects_last_updated_trace_at

ALTER TABLE projects ADD COLUMN last_updated_trace_at TIMESTAMP(6) DEFAULT NULL;

--rollback ALTER TABLE projects DROP COLUMN last_updated_trace_at;

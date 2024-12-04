--liquibase formatted sql
--changeset idoberko2:add_projects_last_updated_trace_at

ALTER TABLE projects ADD COLUMN last_updated_trace_at TIMESTAMP(6) NOT NULL DEFAULT '1970-01-01 00:00:01';

--rollback ALTER TABLE projects DROP COLUMN last_updated_trace_at;

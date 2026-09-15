--liquibase formatted sql
--changeset andrescrz:000093_drop_workspace_version_columns
--comment: Drop the workspace-version-determination columns now that V1 is deprecated and V2 is the only navigation. The version endpoint and its service have been removed, so nothing reads or writes these columns. first_trace_reported_at and has_legacy_scores are kept.

ALTER TABLE workspaces
    DROP COLUMN last_known_version,
    DROP COLUMN version_determined_at;

--rollback ALTER TABLE workspaces ADD COLUMN last_known_version ENUM('version_1', 'version_2') DEFAULT NULL AFTER id, ADD COLUMN version_determined_at TIMESTAMP(6) DEFAULT NULL AFTER last_known_version;

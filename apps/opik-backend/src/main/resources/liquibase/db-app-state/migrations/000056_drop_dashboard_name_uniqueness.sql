--liquibase formatted sql
--changeset andriidudar:000056_drop_dashboard_name_uniqueness
--comment: Remove unique constraint on dashboard name to allow duplicate names across scopes

ALTER TABLE dashboards DROP INDEX dashboards_workspace_name_uk;

--rollback -- Rollback is intentionally a no-op. Reinstating the unique constraint is unsafe because
--rollback -- duplicate dashboard names may have been created after this migration ran.
--rollback -- Manual cleanup of duplicates is required before restoring the constraint.

--liquibase formatted sql
--changeset thiagohora:000069_add_has_legacy_scores_to_workspaces
--comment: Track whether a workspace has data in the legacy `feedback_scores` table. Defaults to TRUE (safe-include UNION).

ALTER TABLE workspaces
    ADD COLUMN has_legacy_scores BOOLEAN NOT NULL DEFAULT TRUE AFTER migration_skipped_reason;

--rollback ALTER TABLE workspaces DROP COLUMN has_legacy_scores;


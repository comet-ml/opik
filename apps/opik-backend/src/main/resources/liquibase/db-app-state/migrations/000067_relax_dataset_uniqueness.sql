--liquibase formatted sql
--changeset danield:000067_relax_dataset_uniqueness
--comment: Relax UNIQUE(workspace_id, name) on datasets so v2 (project-scoped) namesakes can coexist across projects, while v1 (project_id IS NULL) keeps per-workspace uniqueness. Implemented via a stored generated column to side-step MySQL NULL-distinct semantics.

ALTER TABLE datasets
    ADD COLUMN unique_key VARCHAR(300) GENERATED ALWAYS AS (
        CASE WHEN project_id IS NULL THEN name
             ELSE CONCAT(project_id, ':', name)
        END
    ) STORED;

ALTER TABLE datasets DROP INDEX datasets_workspace_id_name_uk;
ALTER TABLE datasets ADD CONSTRAINT datasets_workspace_unique_uk UNIQUE (workspace_id, unique_key);

--rollback -- Rollback is intentionally a no-op. Reinstating UNIQUE (workspace_id, name) is unsafe because
--rollback -- this migration enables v2 namesake datasets to coexist across projects with the same name,
--rollback -- so the old constraint cannot be reapplied without data loss. Manual cleanup is required:
--rollback --   1. Identify duplicate (workspace_id, name) groups where any row has project_id IS NOT NULL.
--rollback --   2. Choose which row to keep per group (typically the v1 row if any) and delete or rename the rest.
--rollback --   3. ALTER TABLE datasets DROP INDEX datasets_workspace_unique_uk;
--rollback --   4. ALTER TABLE datasets DROP COLUMN unique_key;
--rollback --   5. ALTER TABLE datasets ADD CONSTRAINT datasets_workspace_id_name_uk UNIQUE (workspace_id, name);

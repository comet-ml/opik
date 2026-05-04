--liquibase formatted sql
--changeset danield:000066_relax_dataset_uniqueness
--comment: Relax UNIQUE(workspace_id, name) on datasets so v2 (project-scoped) namesakes can coexist across projects, while v1 (project_id IS NULL) keeps per-workspace uniqueness. Implemented via a stored generated column to side-step MySQL NULL-distinct semantics.

ALTER TABLE datasets
    ADD COLUMN unique_key VARCHAR(300) GENERATED ALWAYS AS (
        CASE WHEN project_id IS NULL THEN name
             ELSE CONCAT(project_id, ':', name)
        END
    ) STORED;

ALTER TABLE datasets DROP INDEX datasets_workspace_id_name_uk;
ALTER TABLE datasets ADD CONSTRAINT datasets_workspace_unique_uk UNIQUE (workspace_id, unique_key);

--rollback ALTER TABLE datasets DROP INDEX datasets_workspace_unique_uk;
--rollback ALTER TABLE datasets ADD CONSTRAINT datasets_workspace_id_name_uk UNIQUE (workspace_id, name);
--rollback ALTER TABLE datasets DROP COLUMN unique_key;

--liquibase formatted sql
--changeset jverre:000112_drop_dashboard_slug_unique_key
--comment: Drop the dashboard slug unique key and stop requiring a slug. Nothing looks dashboards up by slug, and since dashboard names stopped being unique (000056) this key only rejected valid creates and renames with a 409. The column stays, nullable, so pods from the previous release can still write it during a rolling deploy; drop it in a later release.

ALTER TABLE dashboards
    DROP INDEX dashboards_workspace_slug_uk,
    MODIFY COLUMN slug VARCHAR(150) NULL;

--rollback -- Rollback is intentionally a no-op. Reinstating the unique key or NOT NULL is unsafe because
--rollback -- dashboards created after this migration have no slug. Backfill unique slugs manually first.

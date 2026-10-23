--liquibase formatted sql
--changeset jverre:000112_drop_dashboard_slug
--comment: Drop the dashboard slug. Nothing looks dashboards up by slug, and since dashboard names stopped being unique (000056) its unique key only rejected valid creates and renames with a 409.

ALTER TABLE dashboards
    DROP INDEX dashboards_workspace_slug_uk,
    DROP COLUMN slug;

--rollback ALTER TABLE dashboards ADD COLUMN slug VARCHAR(150) NOT NULL DEFAULT '' AFTER name;

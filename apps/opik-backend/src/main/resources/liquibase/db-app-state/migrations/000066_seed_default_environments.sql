--liquibase formatted sql
--changeset boryst:000066_seed_default_environments
--comment: Backfill default environments (development, staging, production) for every existing workspace.

INSERT IGNORE INTO environments (id, workspace_id, name, color, position)
SELECT UUID(), p.workspace_id, n.name, n.color, n.position
FROM (SELECT DISTINCT workspace_id FROM projects) p
CROSS JOIN (
    SELECT 'development' AS name, '#EF6868' AS color, 0 AS position
    UNION ALL SELECT 'staging', '#F4B400', 1
    UNION ALL SELECT 'production', '#19A979', 2
) n;

-- Backfilled defaults are indistinguishable from environments seeded for new workspaces; rollback is a no-op to avoid deleting user-visible data.
--rollback empty

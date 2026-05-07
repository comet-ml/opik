--liquibase formatted sql
--changeset boryst:000067_reseed_default_environment_colors
--comment: Truncate and reseed default environments with updated colors. Pre-release feature, no production data to preserve.

TRUNCATE TABLE environments;

INSERT INTO environments (id, workspace_id, name, color, position)
SELECT UUID(), p.workspace_id, n.name, n.color, n.position
FROM (SELECT DISTINCT workspace_id FROM projects) p
CROSS JOIN (
    SELECT 'development' AS name, '#945FCF' AS color, 0 AS position
    UNION ALL SELECT 'staging', '#12A4B4', 1
    UNION ALL SELECT 'production', '#19A979', 2
) n;

-- Rollback drops user-visible data and cannot restore prior color values; intentional no-op.
--rollback empty

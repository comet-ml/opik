--liquibase formatted sql
--changeset avinahradau:000105_create_saved_queries
--comment: Queries Ollie pinned to a chart, run by an ollie_chart dashboard widget that references one by id

-- Written only through /v1/internal, so a dashboard's widgets reference SQL Ollie generated rather than carrying SQL
-- the browser could set. Rows are never updated: editing a chart registers a new query and repoints the widget.
CREATE TABLE saved_queries
(
    id           CHAR(36)     NOT NULL,
    workspace_id VARCHAR(150) NOT NULL,
    -- NULL runs the query across the workspace's projects.
    project_id   CHAR(36)     NULL,
    `sql`        MEDIUMTEXT   NOT NULL,
    created_at   TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by   VARCHAR(100) NOT NULL DEFAULT 'admin',

    PRIMARY KEY (id),
    -- Every read is by workspace and id.
    INDEX saved_queries_workspace_id_idx (workspace_id, id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

--rollback DROP TABLE saved_queries;

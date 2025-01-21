--liquibase formatted sql
--changeset BorisTkachenko:000011_add_comments_table

CREATE TABLE IF NOT EXISTS ${ANALYTICS_DB_DATABASE_NAME}.comments
(
    id              FixedString(36),
    entity_id       FixedString(36),
    entity_type     ENUM('trace'),
    project_id      FixedString(36),
    workspace_id    String,
    text            String,
    created_at      DateTime64(9, 'UTC') DEFAULT now64(9),
    last_updated_at DateTime64(9, 'UTC') DEFAULT now64(9),
    created_by      String DEFAULT '',
    last_updated_by String DEFAULT ''
    ) ENGINE = ReplacingMergeTree(last_updated_at)
    ORDER BY (workspace_id, project_id, entity_id, id);

--rollback DROP TABLE IF EXISTS ${ANALYTICS_DB_DATABASE_NAME}.comments;

--liquibase formatted sql
--changeset BorisTkachenko:000015_add_attachments_table

CREATE TABLE IF NOT EXISTS ${ANALYTICS_DB_DATABASE_NAME}.attachments
(
    workspace_id    String,
    container_id    FixedString(36),
    entity_id       FixedString(36),
    entity_type     ENUM('trace', 'span'),
    file_name       String,
    mime_type       String,
    file_size       Int64,
    created_at      DateTime64(9, 'UTC') DEFAULT now64(9),
    last_updated_at DateTime64(9, 'UTC') DEFAULT now64(9),
    deleted_at      DateTime64(9, 'UTC') DEFAULT toDateTime64(0, 9),
    created_by      String DEFAULT '',
    last_updated_by String DEFAULT ''
    ) ENGINE = ReplacingMergeTree(last_updated_at)
    ORDER BY (workspace_id, container_id, entity_type, entity_id, file_name);

--rollback DROP TABLE IF EXISTS ${ANALYTICS_DB_DATABASE_NAME}.attachments;

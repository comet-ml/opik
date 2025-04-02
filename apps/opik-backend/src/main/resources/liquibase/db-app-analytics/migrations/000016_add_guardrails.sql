--liquibase formatted sql
--changeset idoberko2:0000016_add_guardrails


CREATE TABLE IF NOT EXISTS ${ANALYTICS_DB_DATABASE_NAME}.guardrails
(
    entity_id       FixedString(36),
    entity_type     ENUM('unknown' = 0 , 'span' = 1, 'trace' = 2),
    project_id      FixedString(36),
    workspace_id    String,
    name            String,
    passed          Bool,
    config          String,
    details         String,
    created_at      DateTime64(9, 'UTC') DEFAULT now64(9),
    last_updated_at DateTime64(9, 'UTC') DEFAULT now64(9)
    ) ENGINE = ReplacingMergeTree(last_updated_at)
    ORDER BY (workspace_id, project_id, entity_type, entity_id, name);


--rollback DROP TABLE IF EXISTS ${ANALYTICS_DB_DATABASE_NAME}.guardrails;
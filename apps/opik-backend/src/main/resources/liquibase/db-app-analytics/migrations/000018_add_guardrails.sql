--liquibase formatted sql
--changeset idoberko2:0000018_add_guardrails


CREATE TABLE IF NOT EXISTS ${ANALYTICS_DB_DATABASE_NAME}.guardrails
(
    id                  FixedString(36),
    entity_id           FixedString(36),
    entity_type         ENUM('unknown' = 0 , 'span' = 1, 'trace' = 2),
    secondary_entity_id FixedString(36),
    project_id          FixedString(36),
    workspace_id        String,
    name                String,
    result              ENUM('passed' = 0 , 'failed' = 1),
    config              String,
    details             String,
    created_at          DateTime64(9, 'UTC') DEFAULT now64(9),
    last_updated_at     DateTime64(9, 'UTC') DEFAULT now64(9),
    created_by          String,
    last_updated_by     String
    )
    ENGINE = ReplicatedReplacingMergeTree('/clickhouse/tables/{shard}/${ANALYTICS_DB_DATABASE_NAME}/guardrails', '{replica}')
    ORDER BY (workspace_id, project_id, entity_type, entity_id, id)
    SETTINGS index_granularity = 8192;


--rollback DROP TABLE IF EXISTS ${ANALYTICS_DB_DATABASE_NAME}.guardrails;

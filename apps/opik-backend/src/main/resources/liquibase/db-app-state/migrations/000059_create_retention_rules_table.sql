--liquibase formatted sql
--changeset daniela:000059_create_retention_rules_table
--comment: Create retention rules table for data retention management

CREATE TABLE retention_rules (
    id                  CHAR(36)        NOT NULL,
    workspace_id        VARCHAR(150)    NOT NULL,
    project_id          CHAR(36)        DEFAULT NULL,
    level               ENUM('organization', 'workspace', 'project') NOT NULL DEFAULT 'workspace',
    retention           ENUM('short_14d', 'base_60d', 'extended_400d', 'unlimited') NOT NULL,
    apply_to_past       BOOLEAN         NOT NULL DEFAULT FALSE,
    enabled             BOOLEAN         NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMP(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by          VARCHAR(100)    NOT NULL,
    last_updated_at     TIMESTAMP(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    last_updated_by     VARCHAR(100)    NOT NULL,

    PRIMARY KEY (id),
    INDEX idx_active_workspace (enabled, workspace_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

--rollback DROP TABLE retention_rules;

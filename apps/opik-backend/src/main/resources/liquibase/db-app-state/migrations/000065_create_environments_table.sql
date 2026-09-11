--liquibase formatted sql
--changeset boryst:000065_create_environments_table
--comment: Create environments table for workspace-scoped environment CRUD

CREATE TABLE environments (
    id                  CHAR(36)        NOT NULL,
    workspace_id        VARCHAR(150)    NOT NULL,
    name                VARCHAR(150)    NOT NULL,
    description         VARCHAR(500)    DEFAULT NULL,
    color               VARCHAR(20)     NOT NULL DEFAULT 'default',
    position            INT             NOT NULL DEFAULT 0,
    created_at          TIMESTAMP(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by          VARCHAR(100)    NOT NULL DEFAULT 'admin',
    last_updated_at     TIMESTAMP(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    last_updated_by     VARCHAR(100)    NOT NULL DEFAULT 'admin',

    CONSTRAINT environments_pk PRIMARY KEY (id),
    CONSTRAINT environments_workspace_name_uk UNIQUE (workspace_id, name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

--rollback DROP TABLE environments;

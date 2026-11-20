--liquibase formatted sql
--changeset andriidudar:000116_create_views_tables
--comment: Create views and view_versions, the generated HTML views shown in Opik content areas

CREATE TABLE IF NOT EXISTS views
(
    id              CHAR(36)            NOT NULL,
    workspace_id    VARCHAR(150)        NOT NULL,
    area            ENUM ('widget')     NOT NULL,
    name            VARCHAR(150)        NOT NULL,
    description     VARCHAR(1000)       NULL,
    current_version INT                 NOT NULL,
    last_version    INT                 NOT NULL DEFAULT 0,
    created_at      TIMESTAMP(6)        NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by      VARCHAR(100)        NOT NULL DEFAULT 'admin',
    last_updated_at TIMESTAMP(6)        NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    last_updated_by VARCHAR(100)        NOT NULL DEFAULT 'admin',
    deleted_at      TIMESTAMP(6)        NULL,

    CONSTRAINT views_pk PRIMARY KEY (id),
    INDEX views_workspace_id_idx (workspace_id, id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS view_versions
(
    view_id        CHAR(36)                        NOT NULL,
    version        INT                             NOT NULL,
    author         ENUM ('user', 'ollie', 'agent') NOT NULL,
    summary        VARCHAR(500)                    NULL,
    type           ENUM ('html')                   NOT NULL,
    engine_version INT                             NOT NULL,
    inputs         JSON                            NOT NULL,
    libraries      JSON                            NOT NULL,
    html           MEDIUMTEXT                      NOT NULL,
    created_at     TIMESTAMP(6)                    NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by     VARCHAR(100)                    NOT NULL DEFAULT 'admin',

    CONSTRAINT view_versions_pk PRIMARY KEY (view_id, version)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

--rollback DROP TABLE IF EXISTS view_versions;
--rollback DROP TABLE IF EXISTS views;


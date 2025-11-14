--liquibase formatted sql
--changeset thiagohora:000034_create_dashboards_table

CREATE TABLE dashboards (
    id CHAR(36) NOT NULL,
    workspace_id VARCHAR(150) NOT NULL,
    name VARCHAR(120) NOT NULL,
    slug VARCHAR(150) NOT NULL,
    description VARCHAR(1000),
    config JSON NOT NULL,
    created_by VARCHAR(100),
    last_updated_by VARCHAR(100),
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    last_updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT dashboards_pk PRIMARY KEY (id),
    CONSTRAINT dashboards_workspace_name_uk UNIQUE (workspace_id, name),
    CONSTRAINT dashboards_workspace_slug_uk UNIQUE (workspace_id, slug)
);

--rollback DROP TABLE IF EXISTS dashboards;

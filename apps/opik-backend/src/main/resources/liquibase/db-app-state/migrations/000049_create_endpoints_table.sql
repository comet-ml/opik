--liquibase formatted sql
--changeset BalmungSan:create_endpoints_table

CREATE TABLE IF NOT EXISTS endpoints (
    id CHAR(36) NOT NULL,
    project_id CHAR(36) NOT NULL,
    workspace_id VARCHAR(150) NOT NULL,
    name VARCHAR(255) NOT NULL,
    url VARCHAR(2048) NOT NULL,
    secret VARCHAR(500) NOT NULL,
    schema_json LONGTEXT,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by VARCHAR(100) NOT NULL DEFAULT 'admin',
    last_updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    last_updated_by VARCHAR(100) NOT NULL DEFAULT 'admin',
    CONSTRAINT `endpoints_pk` PRIMARY KEY (id),
    CONSTRAINT `endpoints_workspace_project_name_uk` UNIQUE (workspace_id, project_id, name),
    CONSTRAINT `endpoints_project_id_fk` FOREIGN KEY (project_id) REFERENCES projects(id) ON DELETE CASCADE
);

CREATE INDEX endpoints_workspace_project_idx ON endpoints (workspace_id, project_id);

--rollback DROP TABLE IF EXISTS endpoints;

--liquibase formatted sql
--changeset aadereiko:000082_create_agent_insights_jobs_table
--comment: Create the agent_insights_jobs table — per-(workspace, project) Agent Insights report configuration

CREATE TABLE IF NOT EXISTS agent_insights_jobs (
    id CHAR(36) NOT NULL,
    workspace_id VARCHAR(150) NOT NULL,
    project_id CHAR(36) NOT NULL,
    status ENUM('enabled', 'disabled') NOT NULL DEFAULT 'enabled',
    last_triggered_at TIMESTAMP(6) NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by VARCHAR(255) NOT NULL DEFAULT 'admin',
    last_updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    last_updated_by VARCHAR(255) NOT NULL DEFAULT 'admin',
    CONSTRAINT agent_insights_jobs_pk PRIMARY KEY (id),
    CONSTRAINT agent_insights_jobs_ws_project_uk UNIQUE (workspace_id, project_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

--rollback DROP TABLE IF EXISTS agent_insights_jobs;

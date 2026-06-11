--liquibase formatted sql
--changeset petrot:000081_create_agent_insights_issues_tables
--comment: Agent Insights report results. One row per detected issue plus one row per issue x report_day with the daily metrics. Issues are upserted on the natural key (workspace_id, project_id, name) so daily re-runs stay idempotent; status is owned by the user and never touched by the reporting agent.

CREATE TABLE agent_insights_issues
(
    id              CHAR(36)                           NOT NULL,
    workspace_id    VARCHAR(150)                       NOT NULL,
    project_id      CHAR(36)                           NOT NULL,
    status          ENUM ('open','resolved','closed')  NOT NULL DEFAULT 'open',
    name            VARCHAR(255)                       NOT NULL,
    description     TEXT                               NULL,
    query           TEXT                               NULL,
    created_by      VARCHAR(255)                       NOT NULL DEFAULT 'admin',
    created_at      TIMESTAMP(6)                       NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    last_updated_by VARCHAR(255)                       NOT NULL DEFAULT 'admin',
    last_updated_at TIMESTAMP(6)                       NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),

    PRIMARY KEY (id),
    -- Natural key: "one record per issue" per workspace+project; the upsert target for daily report runs
    UNIQUE KEY agent_insights_issues_workspace_project_name_uk (workspace_id, project_id, name)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

CREATE TABLE agent_insights_issues_details
(
    id              CHAR(36)     NOT NULL,
    issue_id        CHAR(36)     NOT NULL,
    workspace_id    VARCHAR(150) NOT NULL,
    project_id      CHAR(36)     NOT NULL,
    count           BIGINT       NOT NULL DEFAULT 0,
    total_count     BIGINT       NOT NULL DEFAULT 0,
    users_impacted  BIGINT       NOT NULL DEFAULT 0,
    total_users     BIGINT       NOT NULL DEFAULT 0,
    metadata        TEXT         NULL,
    report_day      DATE         NOT NULL,
    created_by      VARCHAR(255) NOT NULL DEFAULT 'admin',
    created_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    last_updated_by VARCHAR(255) NOT NULL DEFAULT 'admin',
    last_updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),

    PRIMARY KEY (id),
    -- "One record per issue per report_day"; the upsert target for daily report runs
    UNIQUE KEY agent_insights_issues_details_uk (workspace_id, project_id, issue_id, report_day),
    -- Supports the time-window list query that filters details by report_day before joining issues
    INDEX agent_insights_issues_details_report_day_idx (workspace_id, project_id, report_day)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

--rollback DROP TABLE agent_insights_issues_details;
--rollback DROP TABLE agent_insights_issues;

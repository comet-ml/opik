--liquibase formatted sql
--changeset miguelg:000069_create_ollie_reports_tables
--comment: Create tables for Ollie daily reports and report preferences

CREATE TABLE IF NOT EXISTS ollie_reports (
    id CHAR(36) NOT NULL,
    workspace_id VARCHAR(150) NOT NULL,
    project_id CHAR(36) NOT NULL,
    session_id CHAR(36) NULL,
    content MEDIUMTEXT NULL,
    status ENUM('pending', 'completed', 'failed') NOT NULL DEFAULT 'pending',
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    last_updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT ollie_reports_pk PRIMARY KEY (id),
    INDEX idx_ollie_reports_workspace_project_created (workspace_id, project_id, created_at DESC),
    INDEX idx_ollie_reports_status_created (status, created_at)
);

CREATE TABLE IF NOT EXISTS report_preferences (
    workspace_id VARCHAR(150) NOT NULL,
    workspace_name VARCHAR(150) NOT NULL,
    project_id CHAR(36) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    schedule_time_utc TIME NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    last_updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT report_preferences_pk PRIMARY KEY (workspace_id, project_id)
);

--rollback DROP TABLE IF EXISTS report_preferences;
--rollback DROP TABLE IF EXISTS ollie_reports;

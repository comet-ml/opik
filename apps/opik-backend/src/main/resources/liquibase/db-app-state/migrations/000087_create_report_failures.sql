--liquibase formatted sql
--changeset aadereiko:000087_create_report_failures
--comment: Create the report_failures table — failure history for reports/jobs, keyed by (project_id, type)

CREATE TABLE IF NOT EXISTS report_failures (
    id CHAR(36) NOT NULL,
    workspace_id VARCHAR(150) NOT NULL,
    type ENUM('agent_insights') NOT NULL,
    project_id CHAR(36) NOT NULL,
    reason VARCHAR(255) NOT NULL,
    detail TEXT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by VARCHAR(255) NOT NULL DEFAULT 'admin',
    last_updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    last_updated_by VARCHAR(255) NOT NULL DEFAULT 'admin',
    CONSTRAINT report_failures_pk PRIMARY KEY (id),
    INDEX report_failures_lookup_idx (workspace_id, project_id, type, created_at)
) ENGINE = InnoDB
  -- Match the schema-wide collation so JOINs against agent_insights_jobs (utf8mb4_unicode_ci, see 000085)
  -- don't fail with "illegal mix of collations".
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

--rollback DROP TABLE IF EXISTS report_failures;

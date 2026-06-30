--liquibase formatted sql
--changeset aadereiko:000088_create_report_failures
--comment: Create the generic report_failures table — failure history for reports/jobs, keyed by (type, entity_id)

CREATE TABLE IF NOT EXISTS report_failures (
    id CHAR(36) NOT NULL,
    workspace_id VARCHAR(150) NOT NULL,
    type VARCHAR(100) NOT NULL,
    entity_id CHAR(36) NOT NULL,
    reason VARCHAR(255) NOT NULL,
    detail TEXT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by VARCHAR(255) NOT NULL DEFAULT 'admin',
    last_updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    last_updated_by VARCHAR(255) NOT NULL DEFAULT 'admin',
    CONSTRAINT report_failures_pk PRIMARY KEY (id),
    INDEX report_failures_lookup_idx (workspace_id, type, entity_id, created_at)
) ENGINE = InnoDB
  -- Match the schema-wide collation so JOINs against agent_insights_jobs (utf8mb4_unicode_ci, see 000085)
  -- don't fail with "illegal mix of collations".
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

--rollback DROP TABLE IF EXISTS report_failures;

--liquibase formatted sql
--changeset aadereiko:000089_drop_failure_columns_from_agent_insights_jobs
--comment: Drop the per-job failure columns; run failures now live in the generic report_failures table

ALTER TABLE agent_insights_jobs
    DROP COLUMN last_failure_reason,
    DROP COLUMN last_failure_detail,
    DROP COLUMN last_failed_at;

--rollback ALTER TABLE agent_insights_jobs ADD COLUMN last_failure_reason VARCHAR(255) NULL DEFAULT NULL, ADD COLUMN last_failure_detail TEXT NULL DEFAULT NULL, ADD COLUMN last_failed_at TIMESTAMP(6) NULL DEFAULT NULL;

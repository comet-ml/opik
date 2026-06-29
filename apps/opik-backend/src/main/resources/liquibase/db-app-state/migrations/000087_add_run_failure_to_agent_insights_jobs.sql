--liquibase formatted sql
--changeset aadereiko:000087_add_run_failure_to_agent_insights_jobs
--comment: Add last run failure signal (reason code, detail, timestamp) to agent_insights_jobs

ALTER TABLE agent_insights_jobs
    ADD COLUMN last_failure_reason VARCHAR(255) NULL DEFAULT NULL AFTER last_scan_at,
    ADD COLUMN last_failure_detail TEXT NULL DEFAULT NULL AFTER last_failure_reason,
    ADD COLUMN last_failed_at TIMESTAMP(6) NULL DEFAULT NULL AFTER last_failure_detail;

--rollback ALTER TABLE agent_insights_jobs DROP COLUMN last_failure_reason, DROP COLUMN last_failure_detail, DROP COLUMN last_failed_at;


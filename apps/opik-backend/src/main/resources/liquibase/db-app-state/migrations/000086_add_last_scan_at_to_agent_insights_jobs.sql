--liquibase formatted sql
--changeset aadereiko:000086_add_last_scan_at_to_agent_insights_jobs
--comment: Add last_scan_at to agent_insights_jobs — time a diagnostic report was last generated

ALTER TABLE agent_insights_jobs
    ADD COLUMN last_scan_at TIMESTAMP(6) NULL DEFAULT NULL AFTER status;

--rollback ALTER TABLE agent_insights_jobs DROP COLUMN last_scan_at;

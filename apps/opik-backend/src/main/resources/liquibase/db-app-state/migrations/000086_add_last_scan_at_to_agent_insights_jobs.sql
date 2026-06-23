--liquibase formatted sql
--changeset aadereiko:000086_add_last_scan_at_to_agent_insights_jobs
--comment: Track when a diagnostic report was last generated for a project (stamped on every report, including "all clear"), so the UI can show an accurate "Last scan" time that is unaffected by user-driven status changes.

ALTER TABLE agent_insights_jobs
    ADD COLUMN last_scan_at TIMESTAMP(6) NULL DEFAULT NULL AFTER status;

--rollback ALTER TABLE agent_insights_jobs DROP COLUMN last_scan_at;

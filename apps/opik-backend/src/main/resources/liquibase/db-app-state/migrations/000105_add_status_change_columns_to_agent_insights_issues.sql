--liquibase formatted sql
--changeset yaroslavb:000105_add_status_change_columns_to_agent_insights_issues
--comment: Add close_note, status_changed_by and status_changed_at to agent_insights_issues

-- close_note is the team's reason for closing an issue as not useful. It is kept only while the issue is closed:
-- any other status clears it. Ollie reads it to avoid re-raising the same finding.
-- status_changed_by / status_changed_at record the last user status change (open, resolved or closed), which the
-- Closed issues page shows. last_updated_* cannot serve: the daily report upsert bumps them too.
ALTER TABLE agent_insights_issues
    ADD COLUMN close_note VARCHAR(500) NULL DEFAULT NULL AFTER status,
    ADD COLUMN status_changed_by VARCHAR(255) NULL DEFAULT NULL AFTER close_note,
    ADD COLUMN status_changed_at TIMESTAMP(6) NULL DEFAULT NULL AFTER status_changed_by;

--rollback ALTER TABLE agent_insights_issues DROP COLUMN status_changed_at, DROP COLUMN status_changed_by, DROP COLUMN close_note;


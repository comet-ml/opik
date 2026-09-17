--liquibase formatted sql
--changeset miguelg:000102_add_auto_first_run_columns_to_agent_insights_jobs
--comment: Add auto_first_run_enrolled and auto_first_run_at to agent_insights_jobs

-- auto_first_run_enrolled marks the projects the auto-first-run rollout applies to. It is set by the internal
-- enrolment endpoint and cleared when the rollout is cancelled
--
-- auto_first_run_at is stamped when that automatic run is enqueued. It is what stops the sweep enqueueing a
-- run that is still executing, and paired with last_scan_at it distinguishes a run in progress from a
-- finished one.
-- Scoped to the automatic run only: manual runs do not set it, so existing rows are correctly NULL and need
-- no backfill. `status` keeps meaning "is the daily schedule on".
ALTER TABLE agent_insights_jobs
    ADD COLUMN auto_first_run_enrolled BOOLEAN NOT NULL DEFAULT FALSE AFTER status,
    ADD COLUMN auto_first_run_at TIMESTAMP(6) NULL DEFAULT NULL AFTER auto_first_run_enrolled;

--rollback ALTER TABLE agent_insights_jobs DROP COLUMN auto_first_run_enrolled, DROP COLUMN auto_first_run_at;

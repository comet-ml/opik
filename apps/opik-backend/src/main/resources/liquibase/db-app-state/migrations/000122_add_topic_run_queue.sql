--liquibase formatted sql
--changeset yaroslavb:000122_add_topic_run_queue
--comment: Topics runs across pods: queued and cancelling attempts, their deadline, heartbeat and cancel reason (OPIK-8987)

-- Nothing writes topic_projects' attempt columns yet, so no row holds a value the new ENUMs lose on rollback.
ALTER TABLE topic_projects
    MODIFY run_mode ENUM ('first_build', 'nightly_update', 'labels') NULL,
    MODIFY run_status ENUM ('queued', 'running', 'cancelling', 'saved', 'failed') NULL,
    MODIFY run_error_code ENUM ('llm_timeout', 'llm_error', 'llm_bad_answer', 'window_read', 'invariant', 'timeout',
                                'save', 'internal', 'abandoned', 'cancelled') NULL,
    ADD COLUMN run_queued_at TIMESTAMP(6) NULL AFTER run_finished_at,
    ADD COLUMN deadline_at   TIMESTAMP(6) NULL AFTER run_queued_at,
    ADD COLUMN heartbeat_at  TIMESTAMP(6) NULL AFTER deadline_at,
    ADD COLUMN cancel_reason ENUM ('deadline', 'project_deleted', 'workspace_disabled') NULL AFTER heartbeat_at,
    -- The planner's count of the runs in flight and its read of them, across workspaces.
    ADD INDEX topic_projects_run_status_idx (run_status);

--rollback ALTER TABLE topic_projects DROP INDEX topic_projects_run_status_idx, DROP COLUMN cancel_reason, DROP COLUMN heartbeat_at, DROP COLUMN deadline_at, DROP COLUMN run_queued_at, MODIFY run_error_code ENUM ('llm_timeout', 'llm_error', 'llm_bad_answer', 'window_read', 'invariant', 'timeout', 'save', 'internal', 'abandoned') NULL, MODIFY run_status ENUM ('running', 'saved', 'failed') NULL, MODIFY run_mode ENUM ('first_build', 'nightly_update') NULL;

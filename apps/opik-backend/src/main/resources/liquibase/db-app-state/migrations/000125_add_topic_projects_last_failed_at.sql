--liquibase formatted sql
--changeset yaroslavb:000125_add_topic_projects_last_failed_at
--comment: Topics nightly pause: when the last discovery or update run failed, untouched by labelling passes (OPIK-8988)

ALTER TABLE topic_projects
    ADD COLUMN last_failed_at TIMESTAMP(6) NULL AFTER first_failed_at;

-- A failed discovery or update run as the row's last run: its own end. Otherwise the streak's first failure, the
-- latest time known to be a failure; the pause then ends no later than the true one.
UPDATE topic_projects
SET last_failed_at = IF(run_status = 'failed' AND run_mode <> 'labels', run_finished_at, first_failed_at)
WHERE failed_runs > 0;

--rollback ALTER TABLE topic_projects DROP COLUMN last_failed_at;

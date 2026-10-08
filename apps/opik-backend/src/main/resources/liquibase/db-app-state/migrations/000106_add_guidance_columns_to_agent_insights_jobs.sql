--liquibase formatted sql
--changeset yaroslavb:000106_add_guidance_columns_to_agent_insights_jobs
--comment: Add project guidance and its version tracking to agent_insights_jobs

-- guidance is the team's free-text instruction for Ollie on this project, sent with every run. NULL = none.
-- guidance_version is bumped only when the text changes (0 = never saved).
-- run_guidance_version is stamped when a run is enqueued with the version it carries, and promoted to
-- results_guidance_version when that run's report lands, so the UI can tell results produced under older
-- guidance. results_guidance_version NULL = no run since this feature existed.
ALTER TABLE agent_insights_jobs
    ADD COLUMN guidance TEXT NULL DEFAULT NULL,
    ADD COLUMN guidance_updated_by VARCHAR(255) NULL DEFAULT NULL,
    ADD COLUMN guidance_updated_at TIMESTAMP(6) NULL DEFAULT NULL,
    ADD COLUMN guidance_version INT NOT NULL DEFAULT 0,
    ADD COLUMN run_guidance_version INT NULL DEFAULT NULL,
    ADD COLUMN results_guidance_version INT NULL DEFAULT NULL;

--rollback ALTER TABLE agent_insights_jobs DROP COLUMN results_guidance_version, DROP COLUMN run_guidance_version, DROP COLUMN guidance_version, DROP COLUMN guidance_updated_at, DROP COLUMN guidance_updated_by, DROP COLUMN guidance;


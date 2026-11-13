--liquibase formatted sql
--changeset aswynz:000114_add_single_runs_to_automation_runs
--comment: Let automation_runs hold one row per firing for event triggers, with step outputs and a wait handle

-- Score-triggered automations stay hourly rollups. An event trigger (a prompt committed, an experiment
-- finished, a CI run) is one firing, so its run is one row that carries what the steps produced, the
-- step it is waiting at while a suite runs, and how deep in a chain it sits.
ALTER TABLE automation_runs
    ADD COLUMN kind ENUM('rollup', 'single') NOT NULL DEFAULT 'rollup' AFTER project_id,
    ADD COLUMN status ENUM('pending', 'done', 'partial', 'failed') NOT NULL DEFAULT 'done' AFTER kind,
    ADD COLUMN item_id VARCHAR(255) NULL AFTER trigger_scope,
    ADD COLUMN trigger_payload JSON NULL AFTER item_id,
    ADD COLUMN step_outputs JSON NULL AFTER trigger_payload,
    ADD COLUMN resume_step INT NULL AFTER step_outputs,
    ADD COLUMN waiting_on VARCHAR(255) NULL AFTER resume_step,
    ADD COLUMN depth INT NOT NULL DEFAULT 0 AFTER waiting_on,
    ADD INDEX automation_runs_waiting_on_idx (workspace_id, waiting_on);

--rollback ALTER TABLE automation_runs DROP INDEX automation_runs_waiting_on_idx, DROP COLUMN depth, DROP COLUMN waiting_on, DROP COLUMN resume_step, DROP COLUMN step_outputs, DROP COLUMN trigger_payload, DROP COLUMN item_id, DROP COLUMN status, DROP COLUMN kind;

--changeset aswynz:000114_create_github_integrations
--comment: Create github_integrations, one encrypted token per workspace for the github.check step

CREATE TABLE IF NOT EXISTS github_integrations (
    workspace_id VARCHAR(150) NOT NULL,
    token TEXT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by VARCHAR(100) NOT NULL DEFAULT 'admin',
    last_updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    last_updated_by VARCHAR(100) NOT NULL DEFAULT 'admin',

    CONSTRAINT github_integrations_pk PRIMARY KEY (workspace_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

--rollback DROP TABLE IF EXISTS github_integrations;

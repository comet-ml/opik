--liquibase formatted sql
--changeset aswynz:000113_add_automation_action
--comment: Allow automation_rules to describe a general automation: when an event happens, run ordered steps

-- An automation is the third kind of rule, beside the evaluator and the queue router: the parent row
-- supplies what every rule has, and the subtype below holds the triggers, steps and limits.
ALTER TABLE automation_rules MODIFY COLUMN `action` ENUM('evaluator', 'annotation_queue_router', 'automation') NOT NULL;

--rollback empty

--changeset aswynz:000113_create_automation_rule_automations
--comment: Create automation_rule_automations, the general-automation subtype of automation_rules

-- Mirrors automation_rule_annotation_queue_routers: the primary key is the parent rule's id, and
-- workspace and project are reached by joining the parent. Triggers, steps and limits are JSON because
-- their shape is the API's and changes with it; the executor reads them whole, never by column.
CREATE TABLE IF NOT EXISTS automation_rule_automations (
    id CHAR(36),

    triggers JSON NOT NULL,
    steps JSON NOT NULL,
    `limits` JSON NULL,
    -- Set when a workflow recipe created this automation, so reads can tell which workflows are on.
    recipe_id VARCHAR(50) NULL,
    recipe_version INT NULL,
    created_via ENUM('mcp', 'ui', 'api') NOT NULL DEFAULT 'api',
    -- A daily limit pauses the automation until midnight UTC; the reason is what the API shows back.
    paused_until TIMESTAMP(6) NULL,
    paused_reason VARCHAR(50) NULL,

    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by VARCHAR(100) NOT NULL DEFAULT 'admin',
    last_updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    last_updated_by VARCHAR(100) NOT NULL DEFAULT 'admin',

    CONSTRAINT automation_rule_automations_pk PRIMARY KEY (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

--rollback DROP TABLE IF EXISTS automation_rule_automations;

--changeset aswynz:000113_create_automation_runs
--comment: Create automation_runs and automation_run_steps: one run per automation per hour of firings, with per-step counters

-- Score-triggered automations fire thousands of times a day, so a run is an hourly rollup rather than a
-- row per firing; per-item outcomes live in the ClickHouse history ledger. The unique key is what lets
-- several consumers fold their counts into the same row.
CREATE TABLE IF NOT EXISTS automation_runs (
    id CHAR(36),
    workspace_id VARCHAR(150) NOT NULL,
    automation_id CHAR(36) NOT NULL,
    project_id CHAR(36) NOT NULL,
    window_start TIMESTAMP(6) NOT NULL,
    window_end TIMESTAMP(6) NOT NULL,
    trigger_event VARCHAR(50) NOT NULL,
    trigger_scope ENUM('trace', 'thread') NOT NULL,
    fired INT NOT NULL DEFAULT 0,
    matched INT NOT NULL DEFAULT 0,
    sampled INT NOT NULL DEFAULT 0,
    cost_usd DECIMAL(18, 6) NOT NULL DEFAULT 0,
    -- The run whose write fired this one, so a chain stays traceable end to end.
    parent_run_id CHAR(36) NULL,
    last_error TEXT NULL,
    started_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    finished_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    CONSTRAINT automation_runs_pk PRIMARY KEY (id),
    CONSTRAINT automation_runs_window_uk UNIQUE (automation_id, window_start),
    INDEX automation_runs_workspace_project_idx (workspace_id, project_id, window_start),
    INDEX automation_runs_parent_idx (parent_run_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS automation_run_steps (
    run_id CHAR(36) NOT NULL,
    step_id VARCHAR(100) NOT NULL,
    step_type VARCHAR(50) NOT NULL,
    position INT NOT NULL,
    done INT NOT NULL DEFAULT 0,
    skipped INT NOT NULL DEFAULT 0,
    failed INT NOT NULL DEFAULT 0,
    last_error TEXT NULL,

    CONSTRAINT automation_run_steps_pk PRIMARY KEY (run_id, step_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

--rollback DROP TABLE IF EXISTS automation_run_steps; DROP TABLE IF EXISTS automation_runs;

--changeset aswynz:000113_create_automation_recommendation_dismissals
--comment: Create automation_recommendation_dismissals, the recommendations a project hid for 30 days

CREATE TABLE IF NOT EXISTS automation_recommendation_dismissals (
    workspace_id VARCHAR(150) NOT NULL,
    recommendation_id CHAR(36) NOT NULL,
    project_id CHAR(36) NOT NULL,
    dismissed_until TIMESTAMP(6) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by VARCHAR(100) NOT NULL DEFAULT 'admin',

    CONSTRAINT automation_recommendation_dismissals_pk PRIMARY KEY (workspace_id, recommendation_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

--rollback DROP TABLE IF EXISTS automation_recommendation_dismissals;

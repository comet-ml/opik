--liquibase formatted sql
--changeset yaroslavb:000117_create_topic_nights
--comment: Create topic_nights and topic_night_projects: what each night of the nightly update decided (OPIK-8988)

-- One row per night of the nightly job (the UTC date it runs for): written 'running' when the night starts and again
-- when it ends, also when it looked at no project. A night run again for the same date, by hand, replaces the row and
-- the date's topic_night_projects rows. outcome is 'failed' when the night threw, or when a pod that died left it
-- 'running' (the next night's housekeeping fails it). The state gauges read this table; rows go after
-- topics.build.runsRetention.
CREATE TABLE topic_nights
(
    night       DATE                                   NOT NULL,
    started_at  TIMESTAMP(6)                           NOT NULL,
    finished_at TIMESTAMP(6)                           NULL,
    outcome     ENUM ('running', 'finished', 'failed') NOT NULL,

    PRIMARY KEY (night),
    -- The latest nights, for the state gauges.
    INDEX topic_nights_started_idx (started_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

-- One row per project a night looked at, skipped ones included, written when the night decides it. decision is
-- nightly_update, skip (reason small, deleted, locked, already_saved, paused or no_list), not_reached or count_failed
-- (the project's window count failed); run_id is the run the night started, null when none started. Rows go with
-- their night.
CREATE TABLE topic_night_projects
(
    id           CHAR(36)     NOT NULL,
    night        DATE         NOT NULL,
    workspace_id VARCHAR(150) NOT NULL,
    project_id   CHAR(36)     NULL,
    decision     VARCHAR(32)  NOT NULL,
    reason       VARCHAR(32)  NULL,
    run_id       CHAR(36)     NULL,

    PRIMARY KEY (id),
    -- A night's projects per workspace: the state gauges and the runbook.
    INDEX topic_night_projects_night_idx (night, workspace_id),
    -- Housekeeping: whether an abandoned run's night saw it end.
    INDEX topic_night_projects_run_idx (run_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

--rollback DROP TABLE IF EXISTS topic_night_projects;
--rollback DROP TABLE IF EXISTS topic_nights;

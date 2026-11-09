--liquibase formatted sql
--changeset yaroslavb:000113_create_topics_tables
--comment: Create topics, topic_prototypes, topic_project_states, topic_runs, topic_run_counts, topic_nights and topic_night_projects: a project's topic list, the nightly runs that build it, each saved run's counts, and what each night decided (OPIK-8987)

-- One row per topic a project's nightly run created. An id is never reused: a renamed or merged topic keeps its id, a
-- retired one keeps its row with retired_at set. low_nights is the lifecycle streak of the nightly update (stage 2b);
-- the first build writes it as 0. size is the topic's count in the last saved night's window. revived_at is the last
-- night a retired topic came back live with its id (the nightly update, OPIK-8988); the first build leaves it null.
CREATE TABLE topics
(
    id             CHAR(36)      NOT NULL,
    workspace_id   VARCHAR(150)  NOT NULL,
    project_id     CHAR(36)      NOT NULL,
    name           VARCHAR(255)  NOT NULL,
    definition     TEXT          NOT NULL,
    born           DATE          NOT NULL,
    retired_at     DATE          NULL,
    retired_reason VARCHAR(64)   NULL,
    revived_at     DATE          NULL,
    low_nights     INT           NOT NULL DEFAULT 0,
    size           INT           NOT NULL DEFAULT 0,
    created_at     TIMESTAMP(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    last_updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),

    PRIMARY KEY (id),
    -- A project's live topics: the nightly run's read and the topics API.
    INDEX topics_workspace_project_retired_idx (workspace_id, project_id, retired_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

-- The prototype vectors traces are assigned by: several per topic, and the known-Other ones (topic_id null). vector is
-- 384 little-endian float32 values (1,536 bytes) of unit length. A run replaces a project's whole set in its save
-- transaction, so readers always see one run's set. position is the prototype's index in the set: assignment breaks a
-- tie between two prototypes by the lower index, so readers order by it, and it is the key within the project: the
-- primary key is the read and delete order, so the set needs no second index. vector is VARBINARY, not BLOB, so InnoDB
-- keeps it in the row; a vector of another length fails the insert.
CREATE TABLE topic_prototypes
(
    workspace_id VARCHAR(150)    NOT NULL,
    project_id   CHAR(36)        NOT NULL,
    position     INT             NOT NULL,
    topic_id     CHAR(36)        NULL,
    vector       VARBINARY(1536) NOT NULL,
    created_at   TIMESTAMP(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    PRIMARY KEY (workspace_id, project_id, position)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

-- One row per project with a saved run. rebuilds counts the saved nights and seeds the next run's k-means;
-- window_until is the end of the last saved run's window.
CREATE TABLE topic_project_states
(
    project_id      CHAR(36)     NOT NULL,
    workspace_id    VARCHAR(150) NOT NULL,
    rebuilds        INT          NOT NULL DEFAULT 0,
    first_built_at  TIMESTAMP(6) NULL,
    last_run_id     CHAR(36)     NULL,
    last_success_at TIMESTAMP(6) NULL,
    window_until    TIMESTAMP(6) NULL,
    last_updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),

    PRIMARY KEY (workspace_id, project_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

-- One row per run of the nightly job on a project: written 'running' when the run starts and set to 'saved' in the save
-- transaction, or to 'failed' with a reason. stats is a JSON summary (vectors, unique texts, k, topics, Other share,
-- Qwen calls, seconds, provisional). Rows are deleted after 90 days.
CREATE TABLE topic_runs
(
    run_id        CHAR(36)                                   NOT NULL,
    workspace_id  VARCHAR(150)                               NOT NULL,
    project_id    CHAR(36)                                   NOT NULL,
    mode          ENUM ('first_build', 'nightly_update')     NOT NULL,
    status        ENUM ('running', 'saved', 'failed')        NOT NULL,
    started_at    TIMESTAMP(6)                               NOT NULL,
    finished_at   TIMESTAMP(6)                               NULL,
    error_code    VARCHAR(32)                                NULL,
    error_message VARCHAR(4000)                              NULL,
    stats         JSON                                       NULL,

    PRIMARY KEY (run_id),
    -- A project's runs: the pause, the first-build candidates and the runbook.
    INDEX topic_runs_workspace_project_started_idx (workspace_id, project_id, started_at),
    -- The 90-day cleanup across all workspaces.
    INDEX topic_runs_started_idx (started_at),
    -- The housekeeping's abandoned runs: its FOR UPDATE read locks only the running rows, not every older run.
    INDEX topic_runs_status_started_idx (status, started_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

-- One row per live topic of a saved run, and one for Other (the all-zeros topic_id, as in trace_topics): the run's
-- counts, which the topics API compares between a project's last two saved runs. traces is the topic's count in the
-- run's window; rank is its 1-based rank in the run, null for Other. A share is traces over the sum of the run's rows,
-- as every window trace gets a topic or Other. The save transaction writes them, so a failed run has none. Rows are
-- deleted with their runs, by the runs' started_at.
CREATE TABLE topic_run_counts
(
    run_id       CHAR(36)     NOT NULL,
    workspace_id VARCHAR(150) NOT NULL,
    project_id   CHAR(36)     NOT NULL,
    topic_id     CHAR(36)     NOT NULL,
    traces       BIGINT       NOT NULL,
    `rank`       INT          NULL,
    saved_at     TIMESTAMP(6) NOT NULL,

    PRIMARY KEY (run_id, topic_id),
    -- A project's last two saved runs, for the topics API.
    INDEX topic_run_counts_workspace_project_saved_idx (workspace_id, project_id, saved_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

-- One row per night of the nightly job (the UTC date it runs for): written 'running' when the night starts and again
-- when it ends, also when it looked at no project. A night run again for the same date, by hand, replaces the row and
-- the date's topic_night_projects rows. outcome is 'failed' when the night threw, or when a pod that died left it
-- 'running' (the next night's housekeeping fails it). projects counts the night's projects per decision, as a JSON
-- object. The state gauges read this table; rows go after topics.nightly.runsRetention.
CREATE TABLE topic_nights
(
    night               DATE                                   NOT NULL,
    started_at          TIMESTAMP(6)                           NOT NULL,
    finished_at         TIMESTAMP(6)                           NULL,
    outcome             ENUM ('running', 'finished', 'failed') NOT NULL,
    eligible_workspaces INT                                    NOT NULL DEFAULT 0,
    count_failures      INT                                    NOT NULL DEFAULT 0,
    projects            JSON                                   NULL,

    PRIMARY KEY (night),
    -- The latest nights, for the state gauges.
    INDEX topic_nights_started_idx (started_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

-- One row per project a night looked at, skipped ones included, written when the night decides it. decision is
-- first_build, skip (reason small, deleted, locked, already_saved or paused) or not_reached; run_id is the run the night
-- started, null when none started. A workspace whose count read failed gets one row with decision count_failed and no project.
-- vectors is the project's window count. Rows go with their night.
CREATE TABLE topic_night_projects
(
    id           CHAR(36)     NOT NULL,
    night        DATE         NOT NULL,
    workspace_id VARCHAR(150) NOT NULL,
    project_id   CHAR(36)     NULL,
    vectors      BIGINT       NULL,
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
--rollback DROP TABLE IF EXISTS topic_run_counts;
--rollback DROP TABLE IF EXISTS topic_runs;
--rollback DROP TABLE IF EXISTS topic_project_states;
--rollback DROP TABLE IF EXISTS topic_prototypes;
--rollback DROP TABLE IF EXISTS topics;

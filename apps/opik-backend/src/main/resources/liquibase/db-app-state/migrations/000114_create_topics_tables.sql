--liquibase formatted sql
--changeset yaroslavb:000114_create_topics_tables
--comment: Create topics, topic_prototypes, topic_project_states and topic_runs: a project's topic list and the nightly runs that build it (OPIK-8987)

-- One row per topic a project's nightly run created. An id is never reused: a renamed or merged topic keeps its id, a
-- retired one keeps its row with retired_at set. inactive_since, low_nights, lo7 and hi7 are the lifecycle streaks of
-- the nightly update (stage 2b); the first build writes them as null and 0. size is the topic's count in the last
-- saved night's window.
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
    inactive_since DATE          NULL,
    low_nights     INT           NOT NULL DEFAULT 0,
    lo7            INT           NOT NULL DEFAULT 0,
    hi7            INT           NOT NULL DEFAULT 0,
    size           INT           NOT NULL DEFAULT 0,
    created_at     TIMESTAMP(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at     TIMESTAMP(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),

    PRIMARY KEY (id),
    -- A project's live topics: the nightly run's read and the topics API.
    INDEX topics_project_retired_idx (project_id, retired_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

-- The prototype vectors traces are assigned by: several per topic, and the known-Other ones (topic_id null). vector is
-- 384 little-endian float32 values (1,536 bytes) of unit length. A run replaces a project's whole set in its save
-- transaction, so readers always see one run's set. position is the prototype's index in the set: assignment breaks a
-- tie between two prototypes by the lower index, so readers order by it.
CREATE TABLE topic_prototypes
(
    id           CHAR(36)     NOT NULL,
    workspace_id VARCHAR(150) NOT NULL,
    project_id   CHAR(36)     NOT NULL,
    position     INT          NOT NULL,
    topic_id     CHAR(36)     NULL,
    vector       BLOB         NOT NULL,
    created_at   TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    PRIMARY KEY (id),
    INDEX topic_prototypes_project_idx (project_id)
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
    updated_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),

    PRIMARY KEY (project_id)
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
    -- A project's runs for the runbook, and the 90-day cleanup.
    INDEX topic_runs_project_started_idx (project_id, started_at),
    INDEX topic_runs_started_idx (started_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

--rollback DROP TABLE IF EXISTS topic_runs;
--rollback DROP TABLE IF EXISTS topic_project_states;
--rollback DROP TABLE IF EXISTS topic_prototypes;
--rollback DROP TABLE IF EXISTS topics;

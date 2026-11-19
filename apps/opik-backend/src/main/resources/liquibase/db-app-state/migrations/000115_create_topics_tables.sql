--liquibase formatted sql
--changeset yaroslavb:000115_create_topics_tables
--comment: Create topics, topic_projects and topic_workspaces: a project's topic list and its current state (OPIK-9034)

-- One row per live topic of a project. An id is never reused: a renamed or merged topic keeps its id, and a retired
-- topic's row is deleted (the retire step logs it). born is the UTC date of the night that made the topic, not the
-- time the run ran: the first build writes the night after its start, and a night re-run for a past date writes that
-- date. low_nights is the nightly update's lifecycle streak; the first build writes it as 0. size is the topic's count
-- among the traces the last saved run read.
-- vectors holds the topic's prototype vectors, the ones traces are assigned by: each is TopicEmbedding.DIMENSIONS
-- little-endian float32 values (1,536 bytes) of unit length, in the order the run gave them. A run replaces a project's
-- whole set in its save transaction, so readers always see one run's set. Assignment breaks a tie between two
-- prototypes by their order: the topics in (born, id) order, then each topic's vectors in their stored order. The
-- model of the set is on the project's topic_projects row. MEDIUMBLOB, as a topic can hold
-- more vectors than a BLOB fits; the topics API reads the other columns only.
CREATE TABLE IF NOT EXISTS topics
(
    id              CHAR(36)     NOT NULL,
    workspace_id    VARCHAR(150) NOT NULL,
    project_id      CHAR(36)     NOT NULL,
    name            VARCHAR(255) NOT NULL,
    definition      TEXT         NOT NULL,
    born            DATE         NOT NULL,
    low_nights      INT          NOT NULL DEFAULT 0,
    size            INT          NOT NULL DEFAULT 0,
    vectors         MEDIUMBLOB   NOT NULL,
    created_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    last_updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),

    PRIMARY KEY (id),
    -- A project's topics: the topics API, the night and arrival.
    INDEX topics_workspace_project_idx (workspace_id, project_id),
    CONSTRAINT topics_vectors_chk CHECK (LENGTH(vectors) > 0 AND MOD(LENGTH(vectors), 1536) = 0)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

-- One row per project the topics jobs know: its current state, never its history (each run and night logs itself and
-- emits metrics). The vectors tick creates the row when it first sees the project, a first build when it first starts
-- one; each group below is null until its writer first writes it. Different writers update different groups: the tick
-- the backfill group, a run the run group, the save the list group under a compare-and-set on list_run_id.
--   Backfill (the vectors tick; ClickHouse stays the only source of which traces have no vector):
--   * backfill_started_at: when this backfill began: the tick first saw the project, or reset the backfill after it
--     did not run for the workspace for 2 x topics.vectors.longWindowInterval.
--   * backfill_drained_at: the start of the tick that drained the project since backfill_started_at; null until one
--     does. The first build waits for it.
--   * backfill_last_done_at: the project's latest done trace_summary status, as last discovered.
--   * backfill_traces_total, backfill_traces_done: approximate progress, see TopicProject.
--   List (the save of a run):
--   * saved_runs counts the saved runs, the first build included; first_built_at is the first save; last_success_at
--     the latest one.
--   * list_run_id: the run whose list is saved. window_until: the end of that run's window.
--   * labels_complete is false from the save until the labelling pass has given every trace of that window a
--     trace_topics row of the run; the first-build job resumes the pass while it is false.
--   * other_vectors: the known-Other prototype vectors, in the topics' format; null when the run kept none.
--   * embed_model: the model of the saved set, topics' vectors and other_vectors. Its size is fixed by the BLOB
--     CHECKs and TopicEmbedding.DIMENSIONS, so a set of another size fails on write or read.
--   Run (the current or last attempt, first build or nightly update):
--   * run_id, run_mode, run_status, run_started_at, run_finished_at, run_error_code (null unless failed).
--     A pod that dies mid-attempt leaves it 'running'. The next attempt, under the project's lock, records a 'running'
--     attempt older than the project's lock lease as failed with 'abandoned' and counts it in failed_runs; until then,
--     readers treat that condition as an abandoned attempt.
--   * failed_runs: the failed attempts since the last saved one; first_failed_at: the start of the first of them.
--     The first-build retries and the pause read them.
--   Night (the nightly update):
--   * last_night: the last night that looked at the project. not_reached_nights: the nights in a row whose budget
--     ended before the project. The state gauges read both.
CREATE TABLE IF NOT EXISTS topic_projects
(
    workspace_id          VARCHAR(150) NOT NULL,
    project_id            CHAR(36)     NOT NULL,

    backfill_started_at   TIMESTAMP(6) NULL,
    backfill_drained_at   TIMESTAMP(6) NULL,
    backfill_last_done_at TIMESTAMP(6) NULL,
    backfill_traces_total BIGINT       NOT NULL DEFAULT 0,
    backfill_traces_done  BIGINT       NOT NULL DEFAULT 0,

    saved_runs            INT          NOT NULL DEFAULT 0,
    first_built_at        TIMESTAMP(6) NULL,
    last_success_at       TIMESTAMP(6) NULL,
    list_run_id           CHAR(36)     NULL,
    window_until          TIMESTAMP(6) NULL,
    labels_complete       BOOLEAN      NOT NULL DEFAULT FALSE,
    other_vectors         MEDIUMBLOB   NULL,
    embed_model           VARCHAR(64)  NULL,

    run_id                CHAR(36)     NULL,
    run_mode              ENUM ('first_build', 'nightly_update') NULL,
    run_status            ENUM ('running', 'saved', 'failed') NULL,
    run_started_at        TIMESTAMP(6) NULL,
    run_finished_at       TIMESTAMP(6) NULL,
    run_error_code        ENUM ('qwen_timeout', 'qwen_error', 'qwen_bad_answer', 'window_read', 'invariant', 'timeout',
                                'save', 'internal', 'abandoned') NULL,
    failed_runs           INT          NOT NULL DEFAULT 0,
    first_failed_at       TIMESTAMP(6) NULL,

    last_night            DATE         NULL,
    not_reached_nights    INT          NOT NULL DEFAULT 0,

    created_at            TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    last_updated_at       TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),

    PRIMARY KEY (workspace_id, project_id),
    -- The tick's read of the projects not drained yet.
    INDEX topic_projects_workspace_drained_idx (workspace_id, backfill_drained_at),
    CONSTRAINT topic_projects_other_vectors_chk
        CHECK (other_vectors IS NULL OR (LENGTH(other_vectors) > 0 AND MOD(LENGTH(other_vectors), 1536) = 0)),
    CONSTRAINT topic_projects_list_model_chk
        CHECK (list_run_id IS NULL OR embed_model IS NOT NULL)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

-- One row per workspace the vectors tick has discovered projects of. vectors_checked_at: the last time it did, about
-- once per topics.vectors.longWindowInterval. Older than twice that: the tick has stopped for the workspace, and the
-- projects' backfill_drained_at says nothing about the traces since.
CREATE TABLE IF NOT EXISTS topic_workspaces
(
    workspace_id       VARCHAR(150) NOT NULL,
    vectors_checked_at TIMESTAMP(6) NOT NULL,
    created_at         TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    last_updated_at    TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),

    PRIMARY KEY (workspace_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

--rollback DROP TABLE IF EXISTS topic_workspaces;
--rollback DROP TABLE IF EXISTS topic_projects;
--rollback DROP TABLE IF EXISTS topics;

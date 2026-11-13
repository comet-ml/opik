--liquibase formatted sql
--changeset yaroslavb:000116_create_topic_vector_backfills
--comment: Create topic_vector_backfills: per project, whether the topic vectors backfill has drained, and its progress (OPIK-8981)

-- One row per project the topic vectors tick has seen done trace_summary statuses of. Written only by the leader's
-- tick; read by the topics first build, which waits for a project's drained_at. ClickHouse stays the only source of
-- which traces have no vector: a row only says what the tick last saw.
--   * started_at: when this backfill began: the row was created, or reset after the tick did not run for the workspace
--     for 2 x topics.vectors.longWindowInterval.
--   * checked_at: the last time the tick discovered the workspace's projects (about once per longWindowInterval) or
--     created the row.
--   * drained_at: the start of the tick that drained the project since started_at; NULL until one does.
--   * last_done_at: the project's latest done status, as last discovered; the backfill takes projects in its order.
--   * traces_total, traces_done: approximate progress, see TopicVectorBackfill.
-- The index serves the tick's read of the projects not drained yet.
CREATE TABLE topic_vector_backfills
(
    workspace_id    VARCHAR(150) NOT NULL,
    project_id      CHAR(36)     NOT NULL,
    started_at      TIMESTAMP(6) NOT NULL,
    checked_at      TIMESTAMP(6) NOT NULL,
    drained_at      TIMESTAMP(6) NULL,
    last_done_at    TIMESTAMP(6) NOT NULL,
    traces_total    BIGINT       NOT NULL DEFAULT 0,
    traces_done     BIGINT       NOT NULL DEFAULT 0,
    created_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    last_updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),

    PRIMARY KEY (workspace_id, project_id),
    INDEX topic_vector_backfills_workspace_drained_idx (workspace_id, drained_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

--rollback DROP TABLE IF EXISTS topic_vector_backfills;

--liquibase formatted sql
--changeset collinc:000111_create_ai_label_jobs
--comment: The ai-labels jobs submitted to opik-inference and their in-flight items: what the tick loads and what the in-flight budget sums

-- One row per opik-inference batch job. A job is 'submitted' until its results are written, then 'loaded'; loaded
-- rows are deleted after 7 days, so the table grows with jobs, not traces. bytes is what the job costs against the
-- global in-flight budget while submitted: its items' request bytes plus a charge per engine call, each item at least
-- a floor.
CREATE TABLE ai_label_jobs
(
    job_id       VARCHAR(64)                  NOT NULL,
    workspace_id VARCHAR(150)                 NOT NULL,
    endpoint     ENUM ('decide', 'generate')  NOT NULL,
    cache        BOOLEAN                      NOT NULL,
    item_count   INT                          NOT NULL,
    bytes        BIGINT                       NOT NULL,
    status       ENUM ('submitted', 'loaded') NOT NULL,
    submitted_at TIMESTAMP(6)                 NOT NULL,
    terminal_at  TIMESTAMP(6)                 NULL,

    PRIMARY KEY (job_id),
    -- The submitted jobs every tick polls, the in-flight budget sum over them, and the cleanup of loaded ones.
    INDEX ai_label_jobs_status_idx (status)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

-- One row per (trace, task) of a submitted job, written with the job row in one transaction and deleted with its
-- results, so the table holds only in-flight work and is bounded by the budget. Load builds the status and label rows
-- from these, and the sweep leaves out their traces.
CREATE TABLE ai_label_job_items
(
    job_id           VARCHAR(64)  NOT NULL,
    workspace_id     VARCHAR(150) NOT NULL,
    project_id       CHAR(36)     NOT NULL,
    trace_id         CHAR(36)     NOT NULL,
    trace_created_at TIMESTAMP(6) NOT NULL,
    task             VARCHAR(32)  NOT NULL,
    task_version     INT          NOT NULL,
    renderer_version INT          NOT NULL,
    compaction_level VARCHAR(64)  NOT NULL,
    attempt          TINYINT      NOT NULL,

    PRIMARY KEY (job_id, trace_id, task),
    -- The sweep's in-flight traces, per bucket of workspaces.
    INDEX ai_label_job_items_workspace_trace_idx (workspace_id, trace_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

--rollback DROP TABLE IF EXISTS ai_label_job_items;
--rollback DROP TABLE IF EXISTS ai_label_jobs;


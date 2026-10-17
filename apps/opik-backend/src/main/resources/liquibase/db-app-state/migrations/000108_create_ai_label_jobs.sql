--liquibase formatted sql
--changeset collinc:000108_create_ai_label_jobs
--comment: The ai-labels jobs submitted to opik-inference: what the poller walks and what the in-flight budget sums

-- One row per opik-inference batch job. A job is 'submitted' until its results are written, then 'loaded'; loaded
-- rows are deleted after 7 days, so the table grows with jobs, not traces. engine_calls is what the job costs against
-- the global in-flight budget (decide: the questions summed over its items; generate: its items).
CREATE TABLE ai_label_jobs
(
    job_id         VARCHAR(64)                NOT NULL,
    workspace_id   VARCHAR(150)               NOT NULL,
    endpoint       ENUM ('decide', 'generate') NOT NULL,
    cache          BOOLEAN                    NOT NULL,
    item_count     INT                        NOT NULL,
    engine_calls   INT                        NOT NULL,
    status         ENUM ('submitted', 'loaded') NOT NULL,
    submitted_at   TIMESTAMP(6)               NOT NULL,
    last_polled_at TIMESTAMP(6)               NULL,
    terminal_at    TIMESTAMP(6)               NULL,

    PRIMARY KEY (job_id),
    -- The poller's due jobs (submitted, least recently polled first), the in-flight budget sum over submitted jobs,
    -- and the cleanup of loaded ones.
    INDEX ai_label_jobs_status_polled_idx (status, last_polled_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

--rollback DROP TABLE IF EXISTS ai_label_jobs;

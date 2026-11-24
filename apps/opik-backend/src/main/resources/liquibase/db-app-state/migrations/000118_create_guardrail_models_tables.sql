--liquibase formatted sql
--changeset aliaksandrk:000118_create_guardrail_models_tables
--comment: Create guardrail_models and guardrail_training_runs — the custom classifiers a workspace trains and their training runs (OPIK-9325)

-- A model is what a CUSTOM_CLASSIFIER rule names in its config. It is created together with its first run and holds
-- no status: everything known about its state is two reads on its runs. Its serving run, the most recent COMPLETED
-- one, makes it usable in a rule; its latest run's status is what the Models tab shows beside that. A stored status
-- would only cache those two reads, and could not say "serving, and retraining".
--
-- No foreign keys, like every other state table since 000042.
CREATE TABLE IF NOT EXISTS guardrail_models
(
    id              CHAR(36)      NOT NULL,
    workspace_id    VARCHAR(150)  NOT NULL,
    -- Never changes once created.
    name            VARCHAR(255)  NOT NULL,
    -- Required: the decide question the adapter is trained and served on is rendered from it.
    description     VARCHAR(1000) NOT NULL,
    created_at      TIMESTAMP(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by      VARCHAR(100)  NOT NULL DEFAULT 'admin',
    last_updated_at TIMESTAMP(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    last_updated_by VARCHAR(100)  NOT NULL DEFAULT 'admin',

    CONSTRAINT guardrail_models_pk PRIMARY KEY (id),
    -- A taken name fails the create. Case-insensitive through the schema collation (utf8mb4_unicode_ci), as for
    -- policies. It also serves the models page: the workspace's rows, the name search and the sort, which runs over a
    -- workspace's few models.
    CONSTRAINT guardrail_models_workspace_name_uk UNIQUE (workspace_id, name)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

-- One row per attempt to train a model: inserted QUEUED before any job exists and kept as long as the model. A
-- model's serving run is its most recent COMPLETED one. A retry is a new row with attempt + 1 and no column linking it
-- to the attempt it retries: a model has at most one run in flight, so that attempt is the model's previous run.
--
-- No S3 keys: a run's dataset and adapter keys derive from workspace_id and id. Everything about a run that no query
-- filters on lives in metadata, in named sub-objects: what was submitted, opik-inference's job id, the job's last stage
-- and detail, the failure's reason and retryable flag, the manifest facts after verification, and files.layout, the
-- version of the key layout the run was written with. A fact a query ever needs gets a generated column and an index.
CREATE TABLE IF NOT EXISTS guardrail_training_runs
(
    id              CHAR(36)                                                       NOT NULL,
    model_id        CHAR(36)                                                       NOT NULL,
    workspace_id    VARCHAR(150)                                                   NOT NULL,
    status          ENUM ('QUEUED', 'RUNNING', 'COMPLETED', 'FAILED', 'CANCELLED') NOT NULL,
    -- Why the run ended FAILED or CANCELLED, as the Models tab shows it. Null otherwise.
    status_message  TEXT                                                           NULL,
    -- Which attempt of one training the run is, 1 for the first.
    attempt         SMALLINT                                                       NOT NULL,
    metadata        JSON                                                           NOT NULL,
    created_at      TIMESTAMP(6)                                                   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by      VARCHAR(100)                                                   NOT NULL DEFAULT 'admin',
    -- A finished run's last_updated_at is its finish time, so there are no separate timing columns.
    last_updated_at TIMESTAMP(6)                                                   NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    last_updated_by VARCHAR(100)                                                   NOT NULL DEFAULT 'admin',

    CONSTRAINT guardrail_training_runs_pk PRIMARY KEY (id),
    -- A model's runs: the serving run (COMPLETED, newest first), the latest run, the run in flight, the previous
    -- attempt, the run history and the model's delete.
    INDEX guardrail_training_runs_model_status_created_idx (model_id, status, created_at),
    -- The worker's scan of the runs in flight across workspaces, which the stuck rule and the dataset grace period
    -- read by last_updated_at. Runs in flight are a handful while finished ones accumulate, so the scan stays small.
    INDEX guardrail_training_runs_status_last_updated_idx (status, last_updated_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

--rollback DROP TABLE IF EXISTS guardrail_training_runs;
--rollback DROP TABLE IF EXISTS guardrail_models;

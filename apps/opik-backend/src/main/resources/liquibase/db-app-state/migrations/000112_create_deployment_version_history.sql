--liquibase formatted sql
--changeset aswynz:000112_create_deployment_version_history
--comment: Create deployment_version_history — which Opik versions this install has run, and when each first started

CREATE TABLE IF NOT EXISTS deployment_version_history (
    version VARCHAR(255) NOT NULL,
    first_seen_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    -- The version IS the identity: every backend replica starting the same version races to
    -- insert the same row, and the primary key is what collapses those into one (paired with
    -- INSERT IGNORE in DeploymentVersionDAO). Do not add a surrogate id — it would let N
    -- replicas write N rows for a single deployment.
    CONSTRAINT deployment_version_history_pk PRIMARY KEY (version),
    INDEX deployment_version_history_first_seen_idx (first_seen_at)
) ENGINE = InnoDB
  -- Match the schema-wide collation (see 000085) so this table behaves like its neighbours.
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

--rollback DROP TABLE IF EXISTS deployment_version_history;

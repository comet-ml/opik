--liquibase formatted sql
--changeset thiagohora:000068_create_workspaces_table
--comment: Generic per-workspace metadata table replacing in-memory/Redis-only state for version tracking, first-trace dedup, and migration skip flag. Designed to grow into the long-term workspace metadata table; feature columns are NULLable so rows are upserted on first relevant event.

CREATE TABLE workspaces (
    id                          VARCHAR(150)    NOT NULL,
    last_known_version          ENUM('version_1', 'version_2') DEFAULT NULL,
    version_determined_at       TIMESTAMP(6)    DEFAULT NULL,
    first_trace_reported_at     TIMESTAMP(6)    DEFAULT NULL,
    migration_skipped_at        TIMESTAMP(6)    DEFAULT NULL,
    migration_skipped_reason    VARCHAR(255)    DEFAULT NULL,
    created_at                  TIMESTAMP(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by                  VARCHAR(100)    NOT NULL DEFAULT 'admin',
    last_updated_at             TIMESTAMP(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    last_updated_by             VARCHAR(100)    NOT NULL DEFAULT 'admin',

    PRIMARY KEY (id),
    -- Supports ExperimentProjectMigrationService cycle exclusion list and trapped-count gauge.
    INDEX workspaces_migration_skipped_idx (migration_skipped_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

--rollback DROP TABLE workspaces;


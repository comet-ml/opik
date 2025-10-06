--liquibase formatted sql
--changeset nimrod:000027_add_open_source_welcome_wizard_tracking
--comment: Create open_source_welcome_wizard_tracking table to track OSS welcome wizard completion status

CREATE TABLE open_source_welcome_wizard_tracking (
    workspace_id VARCHAR(150) NOT NULL,
    completed BOOLEAN NOT NULL DEFAULT FALSE,
    email VARCHAR(255),
    role VARCHAR(100),
    integrations JSON,
    join_beta_program BOOLEAN,
    submitted_at TIMESTAMP(6),
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by VARCHAR(100) NOT NULL DEFAULT 'admin',
    last_updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    last_updated_by VARCHAR(100) NOT NULL DEFAULT 'admin',
    CONSTRAINT `open_source_welcome_wizard_tracking_pk` PRIMARY KEY (workspace_id)
);

--rollback DROP TABLE IF EXISTS open_source_welcome_wizard_tracking;

--liquibase formatted sql
--changeset DanielAugusto:000009_add_automation_rule_tables
CREATE TABLE IF NOT EXISTS automation_rules (
    id CHAR(36),
    project_id CHAR(36) NOT NULL,
    workspace_id VARCHAR(150) NOT NULL,

    `action` ENUM('evaluator') NOT NULL,
    name VARCHAR(150) NOT NULL,
    sampling_rate FLOAT NOT NULL CHECK (sampling_rate >= 0 AND sampling_rate <= 1),

    CONSTRAINT `automation_rules_pk` PRIMARY KEY (id),
    INDEX `automation_rules_idx` (workspace_id, project_id, id)
);

CREATE TABLE IF NOT EXISTS automation_rule_evaluators (
    id CHAR(36),

    `type` ENUM('llm_as_judge') NOT NULL,
    code JSON NOT NULL,

    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by VARCHAR(100) NOT NULL DEFAULT 'admin',
    last_updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    last_updated_by VARCHAR(100) NOT NULL DEFAULT 'admin',

    CONSTRAINT `automation_rules_evaluators_pk` PRIMARY KEY (id)
);

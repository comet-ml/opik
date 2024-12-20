--liquibase formatted sql
--changeset DanielAugusto:000007_add_automation_rule_tables
CREATE TABLE IF NOT EXISTS automation_rule_evaluators (
    id CHAR(36),
    project_id CHAR(36) NOT NULL,
    workspace_id VARCHAR(150) NOT NULL,

    evaluator_type CHAR(20) NOT NULL,
    sampling_rate FLOAT NOT NULL CHECK (sampling_rate >= 0 AND sampling_rate <= 1),
    code TEXT NOT NULL,

    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by VARCHAR(100) NOT NULL DEFAULT 'admin',
    last_updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    last_updated_by VARCHAR(100) NOT NULL DEFAULT 'admin',

    CONSTRAINT `automation_rules_pk` PRIMARY KEY (workspace_id, project_id, id)
 );



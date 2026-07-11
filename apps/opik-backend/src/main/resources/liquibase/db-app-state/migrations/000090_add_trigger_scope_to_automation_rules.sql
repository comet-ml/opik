--liquibase formatted sql
--changeset miguelg:000090_add_trigger_scope_to_automation_rules

ALTER TABLE automation_rules ADD COLUMN trigger_scope ENUM('production', 'experiment', 'both') NOT NULL DEFAULT 'production';

--rollback ALTER TABLE automation_rules DROP COLUMN trigger_scope;

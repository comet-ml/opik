--liquibase formatted sql
--changeset yariv:000022_add_enabled_to_automation_rules

ALTER TABLE automation_rules ADD COLUMN enabled BOOLEAN NOT NULL DEFAULT TRUE;

--rollback ALTER TABLE automation_rules DROP COLUMN enabled;

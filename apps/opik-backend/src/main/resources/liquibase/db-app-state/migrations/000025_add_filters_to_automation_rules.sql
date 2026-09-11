--liquibase formatted sql
--changeset yariv:000025_add_filters_to_automation_rules
--comment: Add filters column to automation_rules table to support filtering traces before applying online scoring

ALTER TABLE automation_rules ADD COLUMN filters JSON NULL;

--rollback ALTER TABLE automation_rules DROP COLUMN filters;
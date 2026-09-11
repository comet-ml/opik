--liquibase formatted sql
--changeset BorisTkachenko:000028_add_alert_type_and_metadata
--comment: Add alert_type and metadata columns to alerts table

-- Add alert_type column with default value 'general'
ALTER TABLE alerts
    ADD COLUMN alert_type ENUM('general', 'slack', 'pagerduty') NOT NULL DEFAULT 'general';

-- Add metadata column as nullable JSON
ALTER TABLE alerts
    ADD COLUMN metadata JSON NULL;

--rollback ALTER TABLE alerts DROP COLUMN metadata;
--rollback ALTER TABLE alerts DROP COLUMN alert_type;


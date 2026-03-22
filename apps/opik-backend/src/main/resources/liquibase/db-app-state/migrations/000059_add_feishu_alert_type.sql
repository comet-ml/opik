--liquibase formatted sql
--changeset DanielArian:000059_add_feishu_alert_type
--comment: Add 'feishu' to the alert_type ENUM column

ALTER TABLE alerts
    MODIFY COLUMN alert_type ENUM('general', 'slack', 'pagerduty', 'feishu') NOT NULL DEFAULT 'general';

--rollback ALTER TABLE alerts MODIFY COLUMN alert_type ENUM('general', 'slack', 'pagerduty') NOT NULL DEFAULT 'general';

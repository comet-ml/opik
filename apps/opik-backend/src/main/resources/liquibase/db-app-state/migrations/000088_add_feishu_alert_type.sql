--liquibase formatted sql
--changeset rusherman:000088_add_feishu_alert_type
--comment: Add 'feishu' to the alerts.alert_type ENUM for the Feishu (Lark) native alert destination. Forward-only (mirrors 000027/000029): shrinking the ENUM back is unsafe once rows use alert_type='feishu'.

ALTER TABLE alerts
    MODIFY COLUMN alert_type ENUM('general', 'slack', 'pagerduty', 'feishu') NOT NULL DEFAULT 'general';

--rollback empty


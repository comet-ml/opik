--liquibase formatted sql
--changeset rusherman:000088_add_feishu_alert_type
--comment: Add 'feishu' to the alerts.alert_type ENUM for the Feishu (Lark) native alert destination

ALTER TABLE alerts
    MODIFY COLUMN alert_type ENUM('general', 'slack', 'pagerduty', 'feishu') NOT NULL DEFAULT 'general';

-- forward-only: shrinking the ENUM back is unsafe once rows use alert_type='feishu'.
-- To revert, first delete or migrate those rows, then restore the previous ENUM definition from backup.

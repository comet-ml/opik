--liquibase formatted sql
--changeset yaricom:000076_add_group_index_to_alert_trigger_configs

ALTER TABLE alert_trigger_configs
    ADD COLUMN group_index INT NULL AFTER config_value;

--rollback ALTER TABLE alert_trigger_configs DROP COLUMN group_index;
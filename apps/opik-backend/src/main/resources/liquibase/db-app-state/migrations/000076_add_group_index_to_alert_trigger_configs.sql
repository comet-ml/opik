--liquibase formatted sql
--changeset yaricom:000076_add_group_index_to_alert_trigger_configs
--comment: Add group_index to alert_trigger_configs to support DNF grouping of metric trigger conditions (AND within same group_index, OR across groups; NULL = legacy singleton group).

ALTER TABLE alert_trigger_configs
    ADD COLUMN group_index INT NULL AFTER config_value;

--rollback ALTER TABLE alert_trigger_configs DROP COLUMN group_index;

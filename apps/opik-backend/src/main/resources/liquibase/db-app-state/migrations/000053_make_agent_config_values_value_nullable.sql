--liquibase formatted sql
--changeset petrot:000053_make_agent_config_values_value_nullable
--comment: Allow null values in agent_config_values.value to support optional fields (e.g. SDK configs where a field has no value assigned yet)

ALTER TABLE agent_config_values MODIFY COLUMN `value` TEXT NULL;

--rollback ALTER TABLE agent_config_values MODIFY COLUMN `value` TEXT NOT NULL;

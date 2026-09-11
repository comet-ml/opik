--liquibase formatted sql
--changeset petrot:000053_make_agent_config_values_value_nullable
--comment: Allow null values in agent_config_values.value to support optional fields (e.g. SDK configs where a field has no value assigned yet)

ALTER TABLE agent_config_values MODIFY COLUMN `value` TEXT NULL;

--rollback -- Rollback is intentionally a no-op. Reverting to NOT NULL is unsafe because NULL rows
--rollback -- may have been inserted after this migration ran. Clean or migrate those rows manually
--rollback -- before attempting to restore the NOT NULL constraint.

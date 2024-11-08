--liquibase formatted sql
--changeset thiagohora:add_prompt_version_to_experiments

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.experiments
    ADD COLUMN IF NOT EXISTS prompt_id Nullable(FixedString(36)) DEFAULT NULL;

--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.experiments DROP COLUMN IF EXISTS prompt_id;

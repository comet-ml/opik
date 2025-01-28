--liquibase formatted sql
--changeset thiagohora:add_prompt_versions_to_experiments

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.experiments
    ADD COLUMN IF NOT EXISTS prompt_versions Map(FixedString(36), Array(FixedString(36)));

--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.experiments DROP COLUMN IF EXISTS prompt_versions;

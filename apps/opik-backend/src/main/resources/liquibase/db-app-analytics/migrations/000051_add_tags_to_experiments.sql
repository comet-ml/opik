--liquibase formatted sql
--changeset iaroslavomelianenko:000051_add_tags_to_experiments
--comment: Add tags column to experiments table

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.experiments ON CLUSTER '{cluster}'
    ADD COLUMN IF NOT EXISTS tags Array(String);

--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.experiments ON CLUSTER '{cluster}' DROP COLUMN IF EXISTS tags;

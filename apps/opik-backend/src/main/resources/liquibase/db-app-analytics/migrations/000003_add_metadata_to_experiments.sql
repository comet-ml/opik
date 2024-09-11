--liquibase formatted sql
--changeset andrescrz:add_metadata_to_experiments

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.experiments
    ADD COLUMN metadata String DEFAULT '';

--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.experiments DROP COLUMN metadata;

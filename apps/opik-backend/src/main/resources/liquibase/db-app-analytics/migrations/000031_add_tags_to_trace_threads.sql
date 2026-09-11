--liquibase formatted sql
--changeset BorisTkachenko:000031_add_tags_to_trace_threads

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.trace_threads ON CLUSTER '{cluster}'
    ADD COLUMN IF NOT EXISTS tags Array(String);

--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.trace_threads ON CLUSTER '{cluster}' DROP COLUMN IF EXISTS tags;

--liquibase formatted sql
--changeset thiagohora:000033_add_trace_thread_scored_at_column

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.trace_threads ON CLUSTER '{cluster}'
    ADD COLUMN IF NOT EXISTS scored_at Nullable(DateTime64(9, 'UTC')) DEFAULT NULL;

-- rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.trace_threads ON CLUSTER '{cluster}' DROP COLUMN IF EXISTS scored_at;

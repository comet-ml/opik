--liquibase formatted sql
--changeset andrescrz:000019_reduce_trace_last_updated_at_to_micros

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces
    MODIFY COLUMN last_updated_at DateTime64(6, 'UTC') DEFAULT now64(6);

--rollback empty

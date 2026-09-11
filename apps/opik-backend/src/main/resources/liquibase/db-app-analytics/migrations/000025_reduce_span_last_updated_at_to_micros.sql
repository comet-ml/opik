--liquibase formatted sql
--changeset andrescrz:000025_reduce_span_last_updated_at_to_micros

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.spans ON CLUSTER '{cluster}'
    MODIFY COLUMN IF EXISTS last_updated_at DateTime64(6, 'UTC') DEFAULT now64(6);

--rollback empty

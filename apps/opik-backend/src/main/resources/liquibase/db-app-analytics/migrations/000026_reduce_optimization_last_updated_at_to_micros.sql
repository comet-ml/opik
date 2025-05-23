--liquibase formatted sql
--changeset BorisTkachenko:000026_reduce_optimization_last_updated_at_to_micros

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.optimizations ON CLUSTER '{cluster}'
    MODIFY COLUMN IF EXISTS last_updated_at DateTime64(6, 'UTC') DEFAULT now64(6);

--rollback empty

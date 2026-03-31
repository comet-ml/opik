--liquibase formatted sql
--changeset thiaghora:000077_add_thread_id_skip_index_to_traces
--comment: Add bloom filter skip index on thread_id in traces to speed up thread view queries (OPIK-4828)

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces ON CLUSTER '{cluster}'
    ADD INDEX IF NOT EXISTS idx_traces_thread_id_bf thread_id TYPE bloom_filter(0.01) GRANULARITY 1;

--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces ON CLUSTER '{cluster}' DROP INDEX IF EXISTS idx_traces_thread_id_bf;

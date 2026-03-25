--liquibase formatted sql
--changeset thiagohora:000032_add_trace_thread_sampling_column

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.trace_threads ON CLUSTER '${ANALYTICS_DB_CLUSTER_NAME}'
    ADD COLUMN IF NOT EXISTS sampling_per_rule Map(FixedString(36), Boolean) DEFAULT map();

-- rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.trace_threads ON CLUSTER '${ANALYTICS_DB_CLUSTER_NAME}' DROP COLUMN IF EXISTS sampling_per_rule;

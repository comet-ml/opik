--liquibase formatted sql
--changeset claude:000045_add_performance_indexes
--comment: Add bloom filter indexes for improved query performance on traces and spans tables

-- Performance improvement: Add bloom filter index on thread_id for faster thread-based queries
-- This index significantly speeds up queries that filter by thread_id
ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces ON CLUSTER '{cluster}'
ADD INDEX IF NOT EXISTS idx_thread_id thread_id
TYPE bloom_filter(0.01) GRANULARITY 1;

-- Performance improvement: Add bloom filter index on tags for faster tag-based filtering
-- This index improves performance for queries that filter or search by tags
ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces ON CLUSTER '{cluster}'
ADD INDEX IF NOT EXISTS idx_tags tags
TYPE bloom_filter(0.01) GRANULARITY 2;

-- Performance improvement: Add bloom filter index on name for faster name-based searches
-- This is useful for filtering traces by their name
ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces ON CLUSTER '{cluster}'
ADD INDEX IF NOT EXISTS idx_name name
TYPE bloom_filter(0.01) GRANULARITY 1;

-- Performance improvement: Add bloom filter index on span name for faster span searches
ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.spans ON CLUSTER '{cluster}'
ADD INDEX IF NOT EXISTS idx_span_name name
TYPE bloom_filter(0.01) GRANULARITY 1;

-- Performance improvement: Add bloom filter index on span type for filtering by span type
ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.spans ON CLUSTER '{cluster}'
ADD INDEX IF NOT EXISTS idx_span_type type
TYPE bloom_filter(0.01) GRANULARITY 1;

-- Note: The indexes will be applied to existing data gradually
-- Run OPTIMIZE TABLE to apply indexes immediately (optional, can be done manually)
-- OPTIMIZE TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces ON CLUSTER '{cluster}' FINAL;
-- OPTIMIZE TABLE ${ANALYTICS_DB_DATABASE_NAME}.spans ON CLUSTER '{cluster}' FINAL;

--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces ON CLUSTER '{cluster}' DROP INDEX IF EXISTS idx_thread_id;
--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces ON CLUSTER '{cluster}' DROP INDEX IF EXISTS idx_tags;
--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces ON CLUSTER '{cluster}' DROP INDEX IF EXISTS idx_name;
--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.spans ON CLUSTER '{cluster}' DROP INDEX IF EXISTS idx_span_name;
--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.spans ON CLUSTER '{cluster}' DROP INDEX IF EXISTS idx_span_type;

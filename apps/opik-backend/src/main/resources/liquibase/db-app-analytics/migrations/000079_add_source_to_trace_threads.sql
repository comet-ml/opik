--liquibase formatted sql
--changeset andrescrz:000079_add_source_to_trace_threads
--comment: Add source column to trace_threads table to track ingestion origin for online-scoring source filter (OPIK-5768)

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.trace_threads ON CLUSTER '{cluster}'
    ADD COLUMN IF NOT EXISTS source Enum8('unknown' = 0, 'sdk' = 1, 'experiment' = 2, 'playground' = 3, 'optimization' = 4) DEFAULT 'unknown';

-- No index added: no current query filters trace_threads by source directly.
-- The source filter is applied in-memory by TraceThreadOnlineScoringSamplerListener.
-- Add a set(0) index later if a ClickHouse query starts filtering by source.

--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.trace_threads ON CLUSTER '{cluster}' DROP COLUMN IF EXISTS source;

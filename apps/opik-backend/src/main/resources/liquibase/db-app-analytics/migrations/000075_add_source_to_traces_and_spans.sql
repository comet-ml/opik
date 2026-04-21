--liquibase formatted sql
--changeset thiagohora:000075_add_source_to_traces_and_spans
--comment: Add source column to traces and spans tables to track ingestion origin (OPIK-5023)

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces ON CLUSTER '{cluster}'
    ADD COLUMN IF NOT EXISTS source Enum8('unknown' = 0, 'sdk' = 1, 'experiment' = 2, 'playground' = 3, 'optimization' = 4) DEFAULT 'unknown';

-- set(0) is used instead of bloom_filter: source has only 5 distinct values (low cardinality),
-- so bloom_filter would say "maybe" for almost every granule and skip nothing.
-- set(0) stores the exact set of values per granule, enabling precise skipping on equality filters.
ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces ON CLUSTER '{cluster}'
    ADD INDEX IF NOT EXISTS idx_traces_source source TYPE set(0) GRANULARITY 1;

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.spans ON CLUSTER '{cluster}'
    ADD COLUMN IF NOT EXISTS source Enum8('unknown' = 0, 'sdk' = 1, 'experiment' = 2, 'playground' = 3, 'optimization' = 4) DEFAULT 'unknown';

-- set(0) is used instead of bloom_filter: same rationale as above.
ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.spans ON CLUSTER '{cluster}'
    ADD INDEX IF NOT EXISTS idx_spans_source source TYPE set(0) GRANULARITY 1;

--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces ON CLUSTER '{cluster}' DROP INDEX IF EXISTS idx_traces_source;
--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces ON CLUSTER '{cluster}' DROP COLUMN IF EXISTS source;
--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.spans ON CLUSTER '{cluster}' DROP INDEX IF EXISTS idx_spans_source;
--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.spans ON CLUSTER '{cluster}' DROP COLUMN IF EXISTS source;


--liquibase formatted sql
--changeset aliaksandrk:000092_add_evaluator_source_value

--comment: Add 'evaluator' to the source Enum8 on traces, spans AND trace_threads so online-evaluation
--comment: monitoring traces/spans (the persisted LLM-as-judge loop) carry their origin and are
--comment: hidden/isolated from default views the same way other non-SDK sources are (OPIK-6994).
--comment: trace_threads MUST stay in sync: thread-resolution queries read source across traces and
--comment: trace_threads, and ClickHouse rejects combining two Enum8 columns with different value sets
--comment: (by type, regardless of data) — leaving trace_threads behind breaks thread creation.

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces ON CLUSTER '{cluster}'
    MODIFY COLUMN IF EXISTS source Enum8('unknown' = 0, 'sdk' = 1, 'experiment' = 2, 'playground' = 3, 'optimization' = 4, 'evaluator' = 5) DEFAULT 'unknown';

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.spans ON CLUSTER '{cluster}'
    MODIFY COLUMN IF EXISTS source Enum8('unknown' = 0, 'sdk' = 1, 'experiment' = 2, 'playground' = 3, 'optimization' = 4, 'evaluator' = 5) DEFAULT 'unknown';

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.trace_threads ON CLUSTER '{cluster}'
    MODIFY COLUMN IF EXISTS source Enum8('unknown' = 0, 'sdk' = 1, 'experiment' = 2, 'playground' = 3, 'optimization' = 4, 'evaluator' = 5) DEFAULT 'unknown';

-- Empty rollback: narrowing the Enum8 back to drop 'evaluator' fails for any traces/spans/threads
-- already written with source='evaluator' (ClickHouse rejects an enum that can't represent existing
-- data). The extra enum value is harmless when unused, so adding it is intentionally not reverted.
--rollback empty

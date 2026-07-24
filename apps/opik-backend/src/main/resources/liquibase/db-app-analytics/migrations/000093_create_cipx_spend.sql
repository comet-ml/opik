--liquibase formatted sql
--changeset boryst:000093_create_cipx_spend
--comment: Dedicated cipx per-call spend table, derived from spans.metadata.cipx

-- AI-spend reads currently fan out from `spans`, scanning the 113 KiB metadata String
-- (99.6% of which is the cipx.blocks array) and JSON-parsing it on every request. This
-- table holds only cipx LLM-call spans, with usage counters and blocks pre-extracted into
-- typed columns, so composition/breakdown reads never touch `spans` or parse JSON.
--
-- One row per span. The blocks live as an Array(Tuple) on the span row rather than in a
-- separate block-level table: a span and all its blocks must replace atomically, and a
-- block-level ReplacingMergeTree would orphan rows when a re-derived span has fewer blocks
-- (the stale rows carry a different sorting key and are never collapsed). Reads expand it
-- with `LEFT ARRAY JOIN blocks AS b` (preserving zero-block spans for the residual).
--
-- ReplacingMergeTree(last_updated_at): the async ingestion delivers at-least-once, so
-- duplicate/replayed rows are expected; FINAL collapses them to one row per sorting key,
-- keeping the highest last_updated_at. last_updated_at uses the column DEFAULT now64(6)
-- (ingestion time), mirroring `spans`, which likewise leaves it to the ClickHouse default:
-- no source timestamp is threaded through, so a create followed by its update collapses to
-- the later-ingested row. ORDER BY mirrors `spans` (workspace_id, project_id, trace_id, id):
-- the time-ordered UUIDv7 trace_id/span_id let reads prune via `trace_id BETWEEN`/`id BETWEEN`
-- ranges, trace_id is also the join key to cipx_trace_identities, and span_id makes the key
-- unique per span so dedup is exact. start_time stays a row-level filter, as in `spans`.
CREATE TABLE IF NOT EXISTS ${ANALYTICS_DB_DATABASE_NAME}.cipx_spends ON CLUSTER '{cluster}'
(
    workspace_id     String,
    project_id       FixedString(36),
    trace_id         FixedString(36),
    span_id          FixedString(36),
    start_time       DateTime64(9, 'UTC'),

    model            LowCardinality(String),

    u_input          Int64,
    u_cache_read     Int64,
    u_cache_creation Int64,
    u_output         Int64,

    -- cipx.blocks[], narrowed to the fields the composition/breakdown queries use.
    -- identity_context/identity_context blocks are dropped at write time, so the stored
    -- array is already query-ready and needs no read-time filtering.
    blocks Array(Tuple(
        category        String,
        side            String,
        cache_status    String,
        parent_category String,
        chars           Int64,
        tool_name       String,
        tool_server     String,
        tool_use_id     String,
        resource        String,
        kind            String
    )),

    last_updated_at  DateTime64(6, 'UTC') DEFAULT now64(6)
)
ENGINE = ReplicatedReplacingMergeTree(
    '/clickhouse/tables/{shard}/${ANALYTICS_DB_DATABASE_NAME}/cipx_spends',
    '{replica}',
    last_updated_at
)
ORDER BY (workspace_id, project_id, trace_id, span_id);

--rollback DROP TABLE IF EXISTS ${ANALYTICS_DB_DATABASE_NAME}.cipx_spends ON CLUSTER '{cluster}';

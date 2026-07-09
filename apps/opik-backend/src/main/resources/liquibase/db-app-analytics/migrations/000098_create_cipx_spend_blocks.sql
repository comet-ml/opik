--liquibase formatted sql
--changeset boryst:000098_create_cipx_spend_blocks
--comment: Dedicated cipx block table, one row per prompt block with allocation precomputed at ingestion

-- Replaces cipx_spends.blocks (Array(Tuple)). The retrieval queries used to ARRAY JOIN that array
-- (~363 blocks/span) and re-derive per-span token allocation with window functions on every request;
-- here each block is a row and the derived columns (tier, lane, bd_lane, label, is_definition, alloc)
-- are computed once at ingestion, so composition/breakdown reads become plain GROUP BYs.
--
-- Two kinds of rows, discriminated by src:
--   'a' (attributed): one row per real cipx block; alloc is the block's chars-proportional share of
--       the span's billed tokens for its tier.
--   'r' (residual): fabricated rows for tiers billed on the span with no blocks to absorb them
--       (lane 'unattributed', empty raw fields); without them totals stop matching billed usage.
--
-- block_idx makes the sorting key unique per row (without it ReplacingMergeTree would collapse all
-- of a span's blocks into one row at merge time) and is deterministic so a replayed insert dedups:
-- attributed rows use the block's raw position in cipx.blocks[] (0..N-1, before identity_context
-- filtering), residual rows use 65531 + tier ordinal (input=0, cache_read=1, cache_creation=2,
-- output=3). Data is immutable (create-only ingestion); ReplacingMergeTree is a replay safeguard
-- only and reads don't use FINAL. Time filtering prunes via the UUIDv7 trace_id range.
CREATE TABLE IF NOT EXISTS ${ANALYTICS_DB_DATABASE_NAME}.cipx_spend_blocks ON CLUSTER '{cluster}'
(
    workspace_id    String,
    project_id      FixedString(36),
    trace_id        FixedString(36),
    span_id         FixedString(36),
    block_idx       UInt16,
    model           LowCardinality(String),
    src             LowCardinality(String),
    -- raw block fields
    category        LowCardinality(String),
    side            LowCardinality(String),
    cache_status    LowCardinality(String),
    parent_category LowCardinality(String),
    chars           Int64,
    tool_name       String,
    tool_server     String,
    tool_use_id     String,
    resource        String,
    kind            LowCardinality(String),
    -- derived at ingestion
    tier            LowCardinality(String),
    lane            LowCardinality(String),
    bd_lane         LowCardinality(String),
    label           String,
    is_definition   UInt8,
    alloc           Float64,

    last_updated_at DateTime64(6, 'UTC') DEFAULT now64(6),
    start_time      DateTime64(9, 'UTC')
)
ENGINE = ReplicatedReplacingMergeTree(
    '/clickhouse/tables/{shard}/${ANALYTICS_DB_DATABASE_NAME}/cipx_spend_blocks',
    '{replica}',
    last_updated_at
)
ORDER BY (workspace_id, project_id, trace_id, span_id, block_idx);

--rollback DROP TABLE IF EXISTS ${ANALYTICS_DB_DATABASE_NAME}.cipx_spend_blocks ON CLUSTER '{cluster}';

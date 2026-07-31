--liquibase formatted sql
--changeset thiagohora:000114_apply_spans_local_v2_real_data_codec_refinements
--comment: Refine spans_local_v2 codecs from real production-data validation (OPIK-7400), metadata-only on the empty table

-- Per-column codec choices validated against a byte-weighted sample of real production `spans` (115,925 deduped rows
-- covering ~94% of the table's payload) on ClickHouse 26.3, the LTS this table is deployed on. spans_local_v2 is still
-- empty (pre-cutover), so each MODIFY COLUMN is metadata-only with no data to re-compress; this MUST run before the
-- Slice-3 backfill, or it degrades into a full re-compress plus a recompute of the materialized columns.
-- Five columns, in two groups. Each is at worst neutral against the codec 000112 ships, so adopting now is low-regret.
--   * usage: ZSTD(1) -> ZSTD(3), 19.3% smaller. 000112 assigned ZSTD(1) as an explicit best guess, this being the one
--     spans-only column with no traces evidence. A Map is stored as an Array(String) keys subcolumn plus an
--     Array(Int64) values subcolumn under the single column codec, and the keys are long, dotted and extremely
--     repetitive -- prod holds 16 distinct key sets in its largest workspace, over names like
--     'original_usage.completion_tokens_details.reasoning_tokens', with the original_usage.* entries repeating the same
--     counts under longer names. That is exactly the small, repetitive, variable-length String shape ClickHouse 26.3
--     regressed ZSTD level 1 on, so the shipped level was the one at risk. ZSTD(3) is also FASTER to decode here (3.6k
--     vs 6.7k CPU-us on a single-thread scan): ZSTD decode is level-independent, so less data simply means less work.
--   * created_at, id_at, start_time: drop Delta, i.e. Delta + ZSTD(1) -> ZSTD(1). Smaller by 18.2%, 5.6% and 2.7%.
--     At microsecond resolution the raw values inside one weekly partition share their high-order bytes and ZSTD's
--     literal matching exploits that prefix directly, while Delta discards it and emits a large high-entropy 8-byte
--     jump at every workspace/project boundary. created_at is the extreme case: it is flat across 46.7% of adjacent
--     row pairs, because batch ingest stamps many spans with the identical microsecond.
--     This deliberately diverges from traces_local_v2, where 000107 restored Delta on real traces data. The divergence
--     is measured, not assumed, and only the columns that agree in two independent scopes (the whole byte-weighted
--     sample, and a single workspace in isolation) are changed here.
-- Deliberately NOT changed, recorded because the evidence is easy to misread later:
--   * end_time and last_updated_at keep Delta + ZSTD(1). On spans they are a wash rather than a win either way:
--     end_time's margin is 0.5%, and last_updated_at flips sign between the two scopes (ZSTD(1) 6.5% smaller over the
--     whole sample, Delta 5.2% smaller within one workspace). A wash does not justify diverging from traces.
--   * The *_length counters keep T64 + ZSTD(1): real data puts T64 14-26% ahead of plain ZSTD(1). A synthetic slice had
--     suggested the opposite, which was an artifact of modelling text sizes as a few discrete tiers.
--   * total_estimated_cost keeps ZSTD(1): ZSTD(3) ties it, and T64 and Delta are both rejected on Decimal(38, 12),
--     since they only accept 1/2/4/8-byte types and it is 128-bit.
--   * model, provider, total_estimated_cost_version and environment keep LowCardinality + ZSTD(1). ZSTD(3) is 2.9-4.6%
--     smaller, which is ~40 MiB across those prod columns -- below materiality, and ZSTD(1) keeps spans aligned with
--     traces. Separately: 000112's comment justifies LowCardinality from a 4M-row sample showing 53 models and 14
--     providers; the true global cardinality is 6,711 and 2,209. Still inside low_cardinality_max_dictionary_size
--     (8192, per part), so the choice holds, but with far less headroom than that comment implies. 000112 is merged and
--     its checksum is immutable, so the correction is recorded here.
-- Columns are ordered as declared in 000112.
ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.spans_local_v2 ON CLUSTER '{cluster}'
    MODIFY COLUMN IF EXISTS start_time  DateTime64(6, 'UTC')   DEFAULT now64(6) CODEC(ZSTD(1)),
    MODIFY COLUMN IF EXISTS usage       Map(String, Int64)     DEFAULT map()    CODEC(ZSTD(3)),
    MODIFY COLUMN IF EXISTS created_at  DateTime64(6, 'UTC')   DEFAULT now64(6) CODEC(ZSTD(1)),
    MODIFY COLUMN IF EXISTS error_info  String                 DEFAULT ''       CODEC(ZSTD(3)),
    MODIFY COLUMN IF EXISTS id_at       DateTime64(0, 'UTC')   MATERIALIZED UUIDv7ToDateTime(toUUID(id)) CODEC(ZSTD(1));

-- Empty rollback: an in-place codec change is not cleanly reversible, and restoring the superseded 000112 codecs is
-- never a wanted recovery step (matches 000106 / 000107 on traces_local_v2).
--rollback empty

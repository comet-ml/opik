--liquibase formatted sql
--changeset thiagohora:000116_apply_spans_local_v2_real_data_codec_refinements
--comment: Refine spans_local_v2 codecs from real production-data validation (OPIK-7400), metadata-only on the empty table

-- Per-column codec choices validated on a byte-weighted sample of real production spans data (OPIK-7400), on the 26.3
-- LTS this table is deployed on. spans_local_v2 is still empty, so each MODIFY COLUMN is metadata-only; this MUST run
-- before any backfill, or it degrades into a full re-compress. Three groups:
--   * usage: ZSTD(1) -> ZSTD(3), 19.3% smaller. 000115 assigned ZSTD(1) as a best guess, this being the one spans-only
--     column with no traces evidence. A Map stores its keys as an Array(String) subcolumn under the one column codec,
--     and they are long, dotted and highly repetitive -- the shape ClickHouse 26.3 regressed ZSTD level 1 on. Decode is
--     also faster, since ZSTD decode is level-independent.
--   * error_info: ZSTD(1) -> ZSTD(3), 8.0% smaller. Mostly empty, but a long repetitive JSON traceback when present, so
--     it belongs with the other structured text.
--   * created_at, id_at, start_time: drop Delta, i.e. Delta + ZSTD(1) -> ZSTD(1). Measured per ISO week, since that is
--     what one partition holds: plain ZSTD(1) is smaller in 10/10 weeks for created_at (median 19%), 9/10 for start_time
--     and 8/10 for id_at. Raw microsecond values within a week share their high-order bytes, which ZSTD matches directly
--     while Delta discards it; created_at is flat across 46.7% of adjacent rows under batch ingest.
-- end_time and last_updated_at keep Delta + ZSTD(1), as 000107 settled for traces: on spans they are a wash (end_time
-- 7/10 weeks, last_updated_at 9/10 but only ~5%), which does not warrant diverging there. Everything else is unchanged;
-- notably the *_length counters keep T64 + ZSTD(1), which real data puts 14-26% ahead of plain ZSTD(1).
-- Columns are ordered as declared in 000115. IF EXISTS keeps the statement idempotent; that a codec actually landed is
-- asserted in CI by SpansLocalV2BenchmarkTest#everySpansLocalV2ColumnUsesItsIntendedCodec.
ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.spans_local_v2 ON CLUSTER '{cluster}'
    MODIFY COLUMN IF EXISTS start_time  DateTime64(6, 'UTC')   DEFAULT now64(6) CODEC(ZSTD(1)),
    MODIFY COLUMN IF EXISTS usage       Map(String, Int64)     DEFAULT map()    CODEC(ZSTD(3)),
    MODIFY COLUMN IF EXISTS created_at  DateTime64(6, 'UTC')   DEFAULT now64(6) CODEC(ZSTD(1)),
    MODIFY COLUMN IF EXISTS error_info  String                 DEFAULT ''       CODEC(ZSTD(3)),
    MODIFY COLUMN IF EXISTS id_at       DateTime64(0, 'UTC')   MATERIALIZED UUIDv7ToDateTime(toUUID(id)) CODEC(ZSTD(1));

-- Empty rollback: an in-place codec change is not cleanly reversible, and restoring the superseded 000115 codecs is
-- never a wanted recovery step (matches 000106 / 000107 on traces_local_v2). Recovery would be a new forward ALTER.
--rollback empty

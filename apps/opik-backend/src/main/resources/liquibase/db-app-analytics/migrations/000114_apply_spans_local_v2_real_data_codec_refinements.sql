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
--   * created_at, id_at, start_time: drop Delta, i.e. Delta + ZSTD(1) -> ZSTD(1). At microsecond resolution the raw
--     values inside one weekly partition share their high-order bytes and ZSTD's literal matching exploits that prefix
--     directly, while Delta discards it and emits a large high-entropy 8-byte jump at every workspace/project boundary.
--     created_at is the extreme case: it is flat across 46.7% of adjacent row pairs, because batch ingest stamps many
--     spans with the identical microsecond.
--     Measured per ISO week, since that is what one partition of this table actually holds -- a codec comparison run
--     over a multi-month sample would misjudge Delta, whose economics are set by the adjacent-row deltas. Across the 10
--     densest weeks of the sample, plain ZSTD(1) is smaller in 10/10 weeks for created_at (median 19%), 9/10 for
--     start_time (median 3.8%) and 8/10 for id_at (median 13%).
--     This deliberately diverges from traces_local_v2, where 000107 restored Delta on real traces data -- but only for
--     end_time and last_updated_at, which are exactly the two columns left untouched below. 000107 never compared these
--     three against plain ZSTD(1) on real data; they carried the original 000101 Delta uncontested.
-- Deliberately NOT changed, recorded because the evidence is easy to misread later:
--   * end_time and last_updated_at keep Delta + ZSTD(1), the codec 000107 settled on for traces after its own real-data
--     pass. On spans, end_time is a genuine wash (plain ZSTD(1) smaller in only 7/10 weeks, every margin under 8% and
--     both signs present). last_updated_at does lean plain ZSTD(1) (9/10 weeks, median 5.0%, one week -13.4%), so there
--     is a case for changing it too; it is left alone here because a ~5% swing on a column that is ~0.03% of the table
--     does not warrant diverging from a merged, real-data-validated traces decision on a same-named column. Worth
--     revisiting jointly with traces if anyone re-runs that pass week-scoped.
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

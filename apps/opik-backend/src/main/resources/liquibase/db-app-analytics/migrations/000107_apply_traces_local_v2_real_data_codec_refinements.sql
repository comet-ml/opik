--liquibase formatted sql
--changeset andrescrz:000107_apply_traces_local_v2_real_data_codec_refinements
--comment: Refine traces_local_v2 codecs from real-data validation (OPIK-6899), metadata-only on the empty table

-- Per-column codec choices validated on real production traces data (OPIK-6899), superseding the synthetic-slice
-- estimates in 000106. traces_local_v2 is still empty, so each MODIFY COLUMN is metadata-only; this MUST run before any
-- backfill, or it degrades into a full re-compress. Two groups:
--   * end_time, last_updated_at: restore Delta + ZSTD(1) (000106 had dropped Delta). 000106 assumed, from the synthetic
--     slice, that these are non-monotonic in the (workspace_id, project_id, id) storage order; on real data they are
--     monotonic enough that Delta is smaller than plain ZSTD(1) (~16% / ~5%). Re-confirm the margin on the full dataset
--     at the backfill (Delta is never materially worse than plain ZSTD(1), so it is safe to adopt now).
--   * workspace_id, name, tags, created_by, last_updated_by, thread_id: ZSTD(1) -> ZSTD(3). ClickHouse 26.3 (the LTS
--     this table is deployed on) regressed ZSTD level 1 on small, repetitive, variable-length String/Array columns,
--     making ZSTD(1) larger than LZ4 on them; ZSTD(3) is unaffected and smallest on both 25.8 and 26.3, at
--     codec-level-independent decode (no read penalty).
-- id/project_id stay ZSTD(1): FixedString UUIDv7, unaffected by the regression and already smallest under ZSTD(1).
-- Everything else (output_keys; input/output/metadata and their slim/truncated forms; the Delta/T64 numeric codecs;
-- floats/enums) is unchanged. Columns are ordered as declared in 000101.
ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces_local_v2 ON CLUSTER '{cluster}'
    MODIFY COLUMN IF EXISTS workspace_id      String                 CODEC(ZSTD(3)),
    MODIFY COLUMN IF EXISTS name              String                 DEFAULT ''       CODEC(ZSTD(3)),
    MODIFY COLUMN IF EXISTS end_time          DateTime64(6, 'UTC')   DEFAULT toDateTime64('1970-01-01 00:00:00', 6) CODEC(Delta, ZSTD(1)),
    MODIFY COLUMN IF EXISTS tags              Array(String)          DEFAULT []       CODEC(ZSTD(3)),
    MODIFY COLUMN IF EXISTS last_updated_at   DateTime64(6, 'UTC')   DEFAULT now64(6) CODEC(Delta, ZSTD(1)),
    MODIFY COLUMN IF EXISTS created_by        String                 DEFAULT ''       CODEC(ZSTD(3)),
    MODIFY COLUMN IF EXISTS last_updated_by   String                 DEFAULT ''       CODEC(ZSTD(3)),
    MODIFY COLUMN IF EXISTS thread_id         String                 DEFAULT ''       CODEC(ZSTD(3));

-- Empty rollback: an in-place codec change is not cleanly reversible, and restoring the superseded 000106 codecs is
-- never a wanted recovery step (matches 000106).
--rollback empty

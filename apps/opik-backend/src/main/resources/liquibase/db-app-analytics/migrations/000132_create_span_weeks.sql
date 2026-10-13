--liquibase formatted sql
--changeset thiagohora:000132_create_span_weeks
--comment: Create span_weeks, the per-trace index of the weekly spans partitions each trace's spans occupy

-- span_weeks records which weekly spans partitions hold each trace's spans, so a trace-keyed spans read can bind its
-- partitions instead of considering every one. A span's week cannot be derived from its trace (span ids may sit in
-- another week, or carry junk dates), so the weeks are taken from the spans themselves. An extra week only adds a
-- partition to a read; a missing one makes it skip rows, so a read may bind the set only once it is complete.
--   * id_week is the spans partition id: the YYYYMMDD of the Monday of the span's id timestamp, computed from the id as
--     spans_local_v2 derives id_at (DateTime64), including its 19691229 (non-v7 ids) and 22991225 (ids at or past 2300)
--     edge weeks. Never from the legacy spans.id_at, which is 32-bit and wraps far-future ids.
--   * The projection serves the per-project weeks for project-scoped reads, which have no trace ids. It is used only by
--     reads shaped IN (SELECT DISTINCT id_week ... AND is_deleted = 0) without FINAL; groupArray/groupUniqArray, a
--     non-DISTINCT IN (SELECT id_week ...), or a filter on a column it does not hold all skip it.
--   * ReplacingMergeTree collapses repeated registrations of the same (trace, week), keeping the latest by
--     last_updated_at; is_deleted tombstones a week, for recomputes, cleanups and deletions. Merges rebuild the
--     projection.
--   * last_updated_at is when the row was last registered, set by ClickHouse on insert (live writes and the backfill
--     both leave it to the default). Every registration of a (trace, week) is a new insert, so it reads as "last seen",
--     not "created".
--   * No TTL: the index must never forget a week. Partitioned by the year of id_week, not by week or month: every read
--     here is time-unbounded and pays per partition. Each spans partition maps to exactly one partition here, so
--     mirroring a spans cleanup is one partition operation.
--   * index_granularity is the default 8192, stated because it cannot change once the table exists; the wide-row
--     index_granularity_bytes of spans/traces would never apply to rows this narrow.
-- Codecs: ZSTD(3) for the short repetitive workspace_id, ZSTD(1) for the UUID ids, T64 + ZSTD(1) for the narrow id_week
-- and Delta + ZSTD(1) for the timestamp, as in spans_local_v2.
CREATE TABLE IF NOT EXISTS ${ANALYTICS_DB_DATABASE_NAME}.span_weeks ON CLUSTER '{cluster}'
(
    workspace_id    String                 CODEC(ZSTD(3)),
    project_id      FixedString(36)        CODEC(ZSTD(1)),
    trace_id        FixedString(36)        CODEC(ZSTD(1)),
    id_week         UInt32                 CODEC(T64, ZSTD(1)),
    last_updated_at DateTime64(6, 'UTC')   DEFAULT now64(6) CODEC(Delta, ZSTD(1)),
    is_deleted      UInt8                  DEFAULT 0,
    PROJECTION prj_project_weeks (
        SELECT workspace_id, project_id, id_week, is_deleted
        GROUP BY workspace_id, project_id, id_week, is_deleted
    )
)
ENGINE = ReplicatedReplacingMergeTree('/clickhouse/tables/{shard}/${ANALYTICS_DB_DATABASE_NAME}/span_weeks', '{replica}',
    last_updated_at, is_deleted)
-- id_week is the spans partition id (the YYYYMMDD of its Monday), so this is its year:
-- each spans partition maps to exactly one partition here.
PARTITION BY intDiv(id_week, 10000)
ORDER BY (workspace_id, project_id, trace_id, id_week)
SETTINGS index_granularity = 8192, deduplicate_merge_projection_mode = 'rebuild',
    lightweight_mutation_projection_mode = 'rebuild';

--rollback DROP TABLE IF EXISTS ${ANALYTICS_DB_DATABASE_NAME}.span_weeks ON CLUSTER '{cluster}';

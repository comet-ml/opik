--liquibase formatted sql
--changeset thiagohora:000130_create_span_weeks
--comment: Create span_weeks, the per-trace index of the weekly spans partitions each trace's spans occupy

-- span_weeks records which weekly spans partitions hold each trace's spans, so a trace-keyed spans read can bind its
-- partitions instead of considering every one. A span's week cannot be derived from its trace (span ids may sit in
-- another week, or carry junk dates), so the weeks are taken from the spans themselves. The set only ever grows:
-- binding it can add a partition to a read, never drop a row.
--   * id_week is the spans partition id: the YYYYMMDD of the Monday of the span's id_at, the same value the spans
--     partition key produces and the read path binds, so no conversion sits between the two.
--   * The projection serves the per-project weeks for project-scoped reads, which have no trace ids.
--   * ReplacingMergeTree collapses repeated registrations of the same (trace, week); merges rebuild the projection.
--   * No TTL and no partitioning: the table is small (~15 bytes per row) and must never forget a week.
-- Whether a project's weeks are complete lives in projects.span_weeks_backfilled (state DB); until it is set, reads
-- for that project stay unbounded.
-- Codecs: ZSTD(3) for the short repetitive workspace_id, ZSTD(1) for the UUID ids, as in spans_local_v2.
CREATE TABLE IF NOT EXISTS ${ANALYTICS_DB_DATABASE_NAME}.span_weeks ON CLUSTER '{cluster}'
(
    workspace_id String          CODEC(ZSTD(3)),
    project_id   FixedString(36) CODEC(ZSTD(1)),
    trace_id     FixedString(36) CODEC(ZSTD(1)),
    id_week      UInt32          CODEC(ZSTD(1)),
    PROJECTION p_project_weeks (
        SELECT workspace_id, project_id, id_week, count()
        GROUP BY workspace_id, project_id, id_week
    )
)
ENGINE = ReplicatedReplacingMergeTree('/clickhouse/tables/{shard}/${ANALYTICS_DB_DATABASE_NAME}/span_weeks', '{replica}')
ORDER BY (workspace_id, project_id, trace_id, id_week)
SETTINGS deduplicate_merge_projection_mode = 'rebuild', lightweight_mutation_projection_mode = 'rebuild';

--rollback DROP TABLE IF EXISTS ${ANALYTICS_DB_DATABASE_NAME}.span_weeks ON CLUSTER '{cluster}';

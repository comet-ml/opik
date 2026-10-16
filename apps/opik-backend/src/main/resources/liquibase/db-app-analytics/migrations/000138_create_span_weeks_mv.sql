--liquibase formatted sql

-- span_weeks_mv registers each inserted span's (project, trace, week) in span_weeks as part of the spans insert itself,
-- so a registration cannot be lost and there is no read-after-write gap. It writes the same rows as SpanWeeksListener
-- (the id_week expression is the backfill's, from SpanWeeksDAO); while both run, span_weeks collapses the duplicates.
--
-- The MV must sit on the LOCAL replicated spans table, never on a Distributed one: a Distributed table stores nothing,
-- so an MV there misses direct inserts into the local table. Exactly one of the two changesets below runs, keyed on
-- whether `spans_local` exists (docs/cutover-table-schema-ddl.md):
--   * no `spans_local`: `spans` is the live local table (pre-cutover, or exchanged but not wrapped) -> FROM spans
--   * `spans_local` exists: `spans` is the Distributed wrap over it (post-cutover)                  -> FROM spans_local
-- Never on the pre-cutover shadow `spans_local_v2`: its backfill copies already-registered rows, and after the EXCHANGE
-- that name holds the old table.
-- An MV is bound to its source table's NAME, not the table: a later RENAME of the source (the cutover wrap renames
-- `spans` to `spans_local`) leaves it on whatever then carries the old name, so the wrap must re-create it on
-- `spans_local` and drop the old one.
--
-- Only inserts fire an MV. Mutations cannot move a span to another week (its key columns are sort-key columns), but
-- partition imports (ATTACH/REPLACE/MOVE PARTITION into the spans table) bypass it and need a span_weeks backfill of
-- the moved range.

--changeset thiagohora:000138_create_span_weeks_mv_pre_cutover
--comment: OPIK-8706 - Pre-cutover branch: spans is the live local table; register span weeks with an MV on it
--preconditions onFail:MARK_RAN onError:HALT
--precondition-sql-check expectedResult:0 SELECT count() FROM system.tables WHERE database = '${ANALYTICS_DB_DATABASE_NAME}' AND name = 'spans_local'
CREATE MATERIALIZED VIEW IF NOT EXISTS ${ANALYTICS_DB_DATABASE_NAME}.span_weeks_mv ON CLUSTER '{cluster}'
TO ${ANALYTICS_DB_DATABASE_NAME}.span_weeks AS
SELECT workspace_id, project_id, trace_id,
    toUInt32(toYYYYMMDD(toDate32(toDateTime64(UUIDv7ToDateTime(toUUID(id)), 0, 'UTC'))
        - toIntervalDay(toDayOfWeek(toDateTime64(UUIDv7ToDateTime(toUUID(id)), 0, 'UTC'), 1)))) AS id_week
FROM ${ANALYTICS_DB_DATABASE_NAME}.spans
GROUP BY workspace_id, project_id, trace_id, id_week;

--rollback DROP VIEW IF EXISTS ${ANALYTICS_DB_DATABASE_NAME}.span_weeks_mv ON CLUSTER '{cluster}' SYNC;

--changeset thiagohora:000138_create_span_weeks_mv_post_cutover
--comment: OPIK-8706 - Post-cutover branch: spans is the Distributed wrapper over spans_local; the MV goes on spans_local
--preconditions onFail:MARK_RAN onError:HALT
--precondition-sql-check expectedResult:1 SELECT count() FROM system.tables WHERE database = '${ANALYTICS_DB_DATABASE_NAME}' AND name = 'spans_local'
CREATE MATERIALIZED VIEW IF NOT EXISTS ${ANALYTICS_DB_DATABASE_NAME}.span_weeks_mv ON CLUSTER '{cluster}'
TO ${ANALYTICS_DB_DATABASE_NAME}.span_weeks AS
SELECT workspace_id, project_id, trace_id,
    toUInt32(toYYYYMMDD(toDate32(toDateTime64(UUIDv7ToDateTime(toUUID(id)), 0, 'UTC'))
        - toIntervalDay(toDayOfWeek(toDateTime64(UUIDv7ToDateTime(toUUID(id)), 0, 'UTC'), 1)))) AS id_week
FROM ${ANALYTICS_DB_DATABASE_NAME}.spans_local
GROUP BY workspace_id, project_id, trace_id, id_week;

--rollback DROP VIEW IF EXISTS ${ANALYTICS_DB_DATABASE_NAME}.span_weeks_mv ON CLUSTER '{cluster}' SYNC;

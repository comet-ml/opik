--liquibase formatted sql
--changeset andrescrz:000119_pin_utc_on_local_v2_epoch_sentinels
--comment: Pin 'UTC' on the epoch sentinel literals in traces_local_v2 and spans_local_v2, metadata-only on empty tables

-- `toDateTime64('1970-01-01 00:00:00', 6)` carries no timezone, so it parses in the SERVER's `timezone` setting, not in
-- the column's. Both tables declare `end_time DateTime64(6, 'UTC')`, so on a server configured to anything but UTC the
-- DEFAULT stores epoch shifted by that offset, and `duration` compares `end_time` against the same shifted instant.
-- Two consequences, and only the second is self-correcting:
--   * `duration`: a row whose `end_time` genuinely IS epoch (not ended yet) no longer equals the shifted sentinel, so the
--     branch yields `dateDiff(...)` instead of NaN. Ingestion writes the DEFAULT through the same shifted expression, so
--     rows written by this server agree with it; rows copied in from a table whose sentinel is true epoch do not, and
--     get a large duration where NaN is meant.
--   * The DEFAULT and the comparison shift together, which is why this is latent rather than a live defect: it is
--     invisible while every server runs UTC, and appears the moment one does not.
-- Pinning 'UTC' makes both expressions depend only on the DDL. Deliberately narrow:
--   * Both tables are empty (traces_local_v2 recreated in 000114, spans_local_v2 created in 000115), so each MODIFY
--     COLUMN is metadata-only and no re-materialization is needed. This MUST run before any backfill into them.
--   * The `traces` / `spans` source tables carry the same unpinned literal (000055) and are NOT touched here. Changing a
--     MATERIALIZED expression does not recompute existing parts, so on a non-UTC server it would leave one table with
--     two sentinels, old rows against the old instant and new rows against the new one, which is worse than the
--     latent asymmetry it fixes. Correcting those needs a MATERIALIZE COLUMN over real data, on its own ticket.
-- Columns are ordered as declared in 000114 / 000115; codecs are carried over unchanged from 000107 / 000116.
ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces_local_v2 ON CLUSTER '{cluster}'
    MODIFY COLUMN IF EXISTS end_time   DateTime64(6, 'UTC') DEFAULT toDateTime64('1970-01-01 00:00:00', 6, 'UTC') CODEC(Delta, ZSTD(1)),
    MODIFY COLUMN IF EXISTS `duration` Float64 MATERIALIZED
        if(end_time = toDateTime64('1970-01-01 00:00:00', 6, 'UTC') OR start_time = toDateTime64('1970-01-01 00:00:00', 6, 'UTC'),
            toFloat64('nan'),
            dateDiff('microsecond', start_time, end_time) / 1000.0) CODEC(ZSTD(1));

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.spans_local_v2 ON CLUSTER '{cluster}'
    MODIFY COLUMN IF EXISTS end_time   DateTime64(6, 'UTC') DEFAULT toDateTime64('1970-01-01 00:00:00', 6, 'UTC') CODEC(Delta, ZSTD(1)),
    MODIFY COLUMN IF EXISTS `duration` Float64 MATERIALIZED
        if(end_time = toDateTime64('1970-01-01 00:00:00', 6, 'UTC') OR start_time = toDateTime64('1970-01-01 00:00:00', 6, 'UTC'),
            toFloat64('nan'),
            dateDiff('microsecond', start_time, end_time) / 1000.0) CODEC(ZSTD(1));

-- Empty rollback: reverting would restore an expression whose meaning depends on the server's timezone, which is never a
-- wanted recovery step. On a UTC server the two are identical anyway.

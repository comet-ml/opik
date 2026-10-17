--liquibase formatted sql

-- span_weeks_mv registers each inserted span's (project, trace, week) in span_weeks as part of the spans insert itself,
-- so a registration cannot be lost and there is no read-after-write gap. It writes the same rows as SpanWeeksListener
-- (the id_week expression is the backfill's, from SpanWeeksDAO); while both run, span_weeks collapses the duplicates.
--
-- The MV is on `spans` because every span insert goes through that name. An MV is bound to its source table's NAME, so
-- the cutover's EXCHANGE and Distributed wrap leave it on whatever then carries `spans`: the live local table before,
-- the Distributed wrapper after, and both see every insert.
--
-- Only inserts fire an MV. Mutations cannot move a span to another week (its key columns are sort-key columns), but
-- partition imports (ATTACH/REPLACE/MOVE PARTITION into the spans table) bypass it and need a span_weeks backfill of
-- the moved range.

--changeset thiagohora:000138_create_span_weeks_mv
--comment: OPIK-8706 - Register span weeks with an MV on spans
CREATE MATERIALIZED VIEW IF NOT EXISTS ${ANALYTICS_DB_DATABASE_NAME}.span_weeks_mv ON CLUSTER '{cluster}'
TO ${ANALYTICS_DB_DATABASE_NAME}.span_weeks AS
SELECT workspace_id, project_id, trace_id,
    toUInt32(toYYYYMMDD(toDate32(toDateTime64(UUIDv7ToDateTime(toUUID(id)), 0, 'UTC'))
        - toIntervalDay(toDayOfWeek(toDateTime64(UUIDv7ToDateTime(toUUID(id)), 0, 'UTC'), 1)))) AS id_week
FROM ${ANALYTICS_DB_DATABASE_NAME}.spans
GROUP BY workspace_id, project_id, trace_id, id_week;

--rollback DROP VIEW IF EXISTS ${ANALYTICS_DB_DATABASE_NAME}.span_weeks_mv ON CLUSTER '{cluster}' SYNC;

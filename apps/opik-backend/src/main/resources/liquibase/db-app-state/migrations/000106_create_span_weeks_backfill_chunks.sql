--liquibase formatted sql
--changeset thiagohora:000106_create_span_weeks_backfill_chunks
--comment: Progress of the span weeks backfill: the span id ranges it planned, and which are done

-- One row per chunk: the span ids from the UUIDv7 of Monday from_week up to, not including, that of Monday to_week
-- (both YYYYMMDD). The chunks tile every id: from_week 0 is open below, for every id older than the install week
-- (past-dated and junk ids included), and a NULL to_week open above, for the plan's own week and anything later.
-- Planned once; a chunk is done once its backfilled_at is set. The index serves the next-pending lookup
-- (backfilled_at IS NULL ordered by from_week) and the completion count.
CREATE TABLE IF NOT EXISTS span_weeks_backfill_chunks (
    from_week     INT UNSIGNED NOT NULL PRIMARY KEY,
    to_week       INT UNSIGNED NULL,
    created_at    TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    backfilled_at TIMESTAMP(6) NULL,
    INDEX span_weeks_backfill_chunks_backfilled_at_idx (backfilled_at, from_week)
);

--rollback DROP TABLE IF EXISTS span_weeks_backfill_chunks;

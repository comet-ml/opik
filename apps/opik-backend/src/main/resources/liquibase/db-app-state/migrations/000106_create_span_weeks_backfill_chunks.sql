--liquibase formatted sql
--changeset thiagohora:000106_create_span_weeks_backfill_chunks
--comment: Progress of the span weeks backfill: the span weeks it planned in chunks, and which chunks are done

-- One row per chunk of consecutive spans weeks (YYYYMMDD Mondays, the spans partition ids), planned once from the
-- weeks the spans table held when the backfill started. A chunk is done once its backfilled_at is set; when every
-- chunk is done, projects.span_weeks_backfilled is set on every project.
CREATE TABLE IF NOT EXISTS span_weeks_backfill_chunks (
    from_week     INT UNSIGNED NOT NULL,
    to_week       INT UNSIGNED NOT NULL,
    span_count    BIGINT       NOT NULL,
    created_at    TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    backfilled_at TIMESTAMP(6) NULL,
    CONSTRAINT `span_weeks_backfill_chunks_pk` PRIMARY KEY (from_week)
);

--rollback DROP TABLE IF EXISTS span_weeks_backfill_chunks;

--changeset thiagohora:000106_add_projects_span_weeks_backfilled_index
--comment: Index the projects the span weeks backfill still has to check, in id order

-- The backfill walks the unmarked projects by id on every step; without it each walk scans the whole table.
CREATE INDEX projects_span_weeks_backfilled_id_idx ON projects (span_weeks_backfilled, id);

--rollback DROP INDEX projects_span_weeks_backfilled_id_idx ON projects;

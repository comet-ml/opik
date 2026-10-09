--liquibase formatted sql
--changeset thiagohora:000105_add_projects_span_weeks_backfilled
--comment: Whether the project's spans are backfilled into the analytics span_weeks index; false keeps its spans reads unbounded

-- Set only after every week of the project's spans is in span_weeks. The UPDATE that sets it must keep
-- last_updated_at unchanged (SET last_updated_at = last_updated_at): the column auto-updates on any write, and this
-- is internal bookkeeping, not a user-visible project change.
ALTER TABLE projects ADD COLUMN span_weeks_backfilled BOOLEAN NOT NULL DEFAULT FALSE;

--rollback ALTER TABLE projects DROP COLUMN span_weeks_backfilled;

--liquibase formatted sql
--changeset thiagohora:000105_add_projects_span_weeks_covered_at
--comment: When the analytics span_weeks index became complete for the project; NULL keeps its spans reads unbounded

-- Set only after every week of the project's spans is in span_weeks. The UPDATE that sets it must keep
-- last_updated_at unchanged (SET last_updated_at = last_updated_at): the column auto-updates on any write, and this
-- is internal bookkeeping, not a user-visible project change.
ALTER TABLE projects ADD COLUMN span_weeks_covered_at TIMESTAMP(6) NULL DEFAULT NULL;

--rollback ALTER TABLE projects DROP COLUMN span_weeks_covered_at;

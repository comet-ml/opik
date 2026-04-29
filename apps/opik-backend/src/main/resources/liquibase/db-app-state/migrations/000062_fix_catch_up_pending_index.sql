--liquibase formatted sql
--changeset daniela:000062_fix_catch_up_pending_index
--comment: Recreate idx_catch_up_pending with equality-first column order for catch-up finder queries

DROP INDEX idx_catch_up_pending ON retention_rules;

-- Covers catch-up finder queries: equality columns first, then cursor for ORDER BY.
-- catch_up_velocity is intentionally excluded — it's a range predicate in all queries,
-- and a B-tree range break prevents subsequent columns from being used for ordering.
-- Velocity filtering happens post-index with negligible cost (small table).
CREATE INDEX idx_catch_up_pending
    ON retention_rules (catch_up_done, enabled, apply_to_past, catch_up_cursor);

--rollback DROP INDEX idx_catch_up_pending ON retention_rules;
--rollback CREATE INDEX idx_catch_up_pending ON retention_rules (catch_up_done, catch_up_velocity);

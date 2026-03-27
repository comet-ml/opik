--liquibase formatted sql
--changeset daniela:000062_add_catch_up_columns_to_retention_rules
--comment: Add catch-up tracking columns for progressive historical data deletion

ALTER TABLE retention_rules
    ADD COLUMN catch_up_velocity    BIGINT          NULL COMMENT 'Estimated spans/week at rule creation time',
    ADD COLUMN catch_up_cursor      CHAR(36)        NULL COMMENT 'UUID v7 catch-up cursor: data before this point has been deleted. Advances oldest to newest. NULL when catch-up is complete.',
    ADD COLUMN catch_up_done        BOOLEAN         NOT NULL DEFAULT TRUE COMMENT 'TRUE when historical catch-up is complete. Only FALSE for apply_to_past rules with pending backfill.';

-- Covers all catch-up finder queries: equality columns first, then velocity for range filtering, cursor for ordering
CREATE INDEX idx_catch_up_pending
    ON retention_rules (catch_up_done, enabled, apply_to_past, catch_up_velocity, catch_up_cursor);

--rollback DROP INDEX idx_catch_up_pending ON retention_rules;
--rollback ALTER TABLE retention_rules DROP COLUMN catch_up_done;
--rollback ALTER TABLE retention_rules DROP COLUMN catch_up_cursor;
--rollback ALTER TABLE retention_rules DROP COLUMN catch_up_velocity;

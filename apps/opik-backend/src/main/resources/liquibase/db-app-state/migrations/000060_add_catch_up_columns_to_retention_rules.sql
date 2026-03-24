--liquibase formatted sql
--changeset daniela:000060_add_catch_up_columns_to_retention_rules
--comment: Add catch-up tracking columns for progressive historical data deletion

ALTER TABLE retention_rules
    ADD COLUMN catch_up_velocity    BIGINT          NULL COMMENT 'Estimated spans/week at rule creation time',
    ADD COLUMN catch_up_cursor      CHAR(36)        NULL COMMENT 'UUID v7 tracking catch-up progress (oldest to newest)',
    ADD COLUMN catch_up_done        BOOLEAN         NOT NULL DEFAULT TRUE COMMENT 'TRUE when catch-up is complete';

-- Index for catch-up queries: filters on (done, enabled, apply_to_past), range on velocity, sort by cursor
CREATE INDEX idx_catch_up_pending
    ON retention_rules (catch_up_done, enabled, apply_to_past, catch_up_velocity, catch_up_cursor);

--rollback DROP INDEX idx_catch_up_pending ON retention_rules;
--rollback ALTER TABLE retention_rules DROP COLUMN catch_up_velocity, DROP COLUMN catch_up_cursor, DROP COLUMN catch_up_done;

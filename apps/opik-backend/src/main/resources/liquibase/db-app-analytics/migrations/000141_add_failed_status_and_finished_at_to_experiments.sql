--liquibase formatted sql
--changeset miguelg:000141_add_failed_status_to_experiments
--comment: Add a 'failed' experiment status. A playground run can now be stopped by the user, so 'cancelled' no longer implies a fault and failures need their own status (OPIK-7789)

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.experiments ON CLUSTER '{cluster}'
    MODIFY COLUMN status Enum8('unknown' = 0, 'running' = 1, 'completed' = 2, 'cancelled' = 3, 'failed' = 4);

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.experiment_aggregates ON CLUSTER '{cluster}'
    MODIFY COLUMN status Enum8('unknown' = 0, 'running' = 1, 'completed' = 2, 'cancelled' = 3, 'failed' = 4) DEFAULT 'unknown';

-- In place, so not reverted: narrowing the Enum8 back fails for any experiment already written with
-- status='failed', since ClickHouse rejects an enum that cannot represent existing data. The extra
-- value is harmless while unused.
--rollback empty

--changeset miguelg:000141_add_finished_at_to_experiments
--comment: Add a finished_at timestamp, recording when a run stopped producing items. 'cancelled' cannot convey that, being written when the stop is requested rather than when it takes effect (OPIK-7789)

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.experiments ON CLUSTER '{cluster}'
    ADD COLUMN IF NOT EXISTS finished_at Nullable(DateTime64(9, 'UTC'));

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.experiment_aggregates ON CLUSTER '{cluster}'
    ADD COLUMN IF NOT EXISTS finished_at Nullable(DateTime64(9, 'UTC'));

--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.experiments ON CLUSTER '{cluster}' DROP COLUMN IF EXISTS finished_at;
--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.experiment_aggregates ON CLUSTER '{cluster}' DROP COLUMN IF EXISTS finished_at;

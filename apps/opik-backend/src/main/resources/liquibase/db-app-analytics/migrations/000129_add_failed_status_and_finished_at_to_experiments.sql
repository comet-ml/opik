--liquibase formatted sql
--changeset miguelg:000129_add_failed_status_and_finished_at_to_experiments
--comment: Add 'failed' experiment status and a finished_at timestamp. A playground run can now be stopped by the user, so 'cancelled' no longer implies a fault and failures need their own status. finished_at records when a run stopped producing items, which 'cancelled' cannot convey because it is written when the stop is requested, not when it takes effect (OPIK-7789)

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.experiments ON CLUSTER '{cluster}'
    MODIFY COLUMN status Enum8('unknown' = 0, 'running' = 1, 'completed' = 2, 'cancelled' = 3, 'failed' = 4);

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.experiment_aggregates ON CLUSTER '{cluster}'
    MODIFY COLUMN status Enum8('unknown' = 0, 'running' = 1, 'completed' = 2, 'cancelled' = 3, 'failed' = 4) DEFAULT 'unknown';

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.experiments ON CLUSTER '{cluster}'
    ADD COLUMN IF NOT EXISTS finished_at Nullable(DateTime64(9, 'UTC'));

--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.experiments ON CLUSTER '{cluster}' DROP COLUMN IF EXISTS finished_at;
--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.experiment_aggregates ON CLUSTER '{cluster}' MODIFY COLUMN status Enum8('unknown' = 0, 'running' = 1, 'completed' = 2, 'cancelled' = 3) DEFAULT 'unknown';
--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.experiments ON CLUSTER '{cluster}' MODIFY COLUMN status Enum8('unknown' = 0, 'running' = 1, 'completed' = 2, 'cancelled' = 3);

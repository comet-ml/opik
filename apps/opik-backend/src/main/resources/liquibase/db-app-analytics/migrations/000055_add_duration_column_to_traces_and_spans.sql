--liquibase formatted sql
--changeset thiagohora:000055_add_duration_column_to_traces
--comment: Add materialized duration columns to traces and spans tables
-- Add materialized duration column to traces table
-- Duration is calculated as the difference between end_time and start_time in milliseconds
-- Returns NULL if end_time or start_time is NULL, or if start_time is the epoch (1970-01-01)

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces ON CLUSTER '{cluster}'
    ADD COLUMN IF NOT EXISTS `duration` Nullable(Float64) MATERIALIZED
        if(end_time IS NOT NULL AND start_time IS NOT NULL
            AND notEquals(start_time, toDateTime64('1970-01-01 00:00:00.000', 9)),
            (dateDiff('microsecond', start_time, end_time) / 1000.0),
            NULL);

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.spans ON CLUSTER '{cluster}'
    ADD COLUMN IF NOT EXISTS `duration` Nullable(Float64) MATERIALIZED
        if(end_time IS NOT NULL AND start_time IS NOT NULL
            AND notEquals(start_time, toDateTime64('1970-01-01 00:00:00.000', 9)),
            (dateDiff('microsecond', start_time, end_time) / 1000.0),
            NULL);
--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces ON CLUSTER '{cluster}' DROP COLUMN IF EXISTS `duration`;
--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.spans ON CLUSTER '{cluster}' DROP COLUMN IF EXISTS `duration`;


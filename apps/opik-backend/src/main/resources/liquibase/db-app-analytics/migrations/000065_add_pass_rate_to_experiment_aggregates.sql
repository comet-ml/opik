--liquibase formatted sql
--changeset thiaghora:000065_add_pass_rate_to_experiment_aggregates
--comment: Add pass_rate, passed_count, and total_count fields to experiment_aggregates table for evaluation suite pass rate tracking

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.experiment_aggregates ON CLUSTER '{cluster}'
    ADD COLUMN IF NOT EXISTS pass_rate Decimal64(9) DEFAULT 0;

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.experiment_aggregates ON CLUSTER '{cluster}'
    ADD COLUMN IF NOT EXISTS passed_count UInt64 DEFAULT 0;

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.experiment_aggregates ON CLUSTER '{cluster}'
    ADD COLUMN IF NOT EXISTS total_count UInt64 DEFAULT 0;

--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.experiment_aggregates ON CLUSTER '{cluster}' DROP COLUMN IF EXISTS total_count;
--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.experiment_aggregates ON CLUSTER '{cluster}' DROP COLUMN IF EXISTS passed_count;
--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.experiment_aggregates ON CLUSTER '{cluster}' DROP COLUMN IF EXISTS pass_rate;

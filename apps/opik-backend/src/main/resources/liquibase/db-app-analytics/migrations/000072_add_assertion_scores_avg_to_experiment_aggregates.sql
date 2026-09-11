--liquibase formatted sql
--changeset danield:000072_add_assertion_scores_avg_to_experiment_aggregates
--comment: Add assertion_scores_avg field to experiment_aggregates table for per-assertion average pass rate tracking

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.experiment_aggregates ON CLUSTER '{cluster}'
    ADD COLUMN IF NOT EXISTS assertion_scores_avg Map(String, Float64) DEFAULT map() CODEC(ZSTD(1));

--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.experiment_aggregates ON CLUSTER '{cluster}' DROP COLUMN IF EXISTS assertion_scores_avg;

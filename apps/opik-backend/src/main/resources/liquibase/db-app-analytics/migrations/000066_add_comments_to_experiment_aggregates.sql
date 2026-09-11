--liquibase formatted sql
--changeset thiaghora:000066_add_comments_to_experiment_aggregates
--comment: Add comments_array_agg column to experiment_aggregates and experiment_item_aggregates tables for storing pre-aggregated comments JSON

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.experiment_aggregates ON CLUSTER '{cluster}'
    ADD COLUMN IF NOT EXISTS comments_array_agg String DEFAULT '' CODEC(ZSTD(3));

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.experiment_item_aggregates ON CLUSTER '{cluster}'
    ADD COLUMN IF NOT EXISTS comments_array_agg String DEFAULT '' CODEC(ZSTD(3));

--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.experiment_item_aggregates ON CLUSTER '{cluster}' DROP COLUMN IF EXISTS comments_array_agg;
--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.experiment_aggregates ON CLUSTER '{cluster}' DROP COLUMN IF EXISTS comments_array_agg;
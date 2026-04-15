--liquibase formatted sql
--changeset thiaghora:000076_add_assertions_array_to_experiment_item_aggregates
--comment: Add assertions_array to experiment_item_aggregates for pre-aggregated assertion data

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.experiment_item_aggregates ON CLUSTER '{cluster}'
    ADD COLUMN IF NOT EXISTS assertions_array String DEFAULT '[]' CODEC(ZSTD(3));

--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.experiment_item_aggregates ON CLUSTER '{cluster}' DROP COLUMN IF EXISTS assertions_array;

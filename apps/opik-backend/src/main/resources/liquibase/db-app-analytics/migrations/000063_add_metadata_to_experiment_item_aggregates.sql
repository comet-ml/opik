--liquibase formatted sql
--changeset thiaghora:000063_add_metadata_to_experiment_item_aggregates
--comment: Add metadata field to experiment_item_aggregates table, populated from the associated trace

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.experiment_item_aggregates ON CLUSTER '{cluster}'
    ADD COLUMN IF NOT EXISTS metadata String DEFAULT '' CODEC(ZSTD(3));

--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.experiment_item_aggregates ON CLUSTER '{cluster}' DROP COLUMN IF EXISTS metadata;

--liquibase formatted sql
--changeset danield:000152_add_minmax_index_experiment_items_id
--comment: Add minmax skip index on experiment_items.id so lookups by experiment item id prune granules instead of reading every granule of the workspace (OPIK-9298)

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.experiment_items ON CLUSTER '{cluster}'
    ADD INDEX IF NOT EXISTS idx_experiment_items_id id TYPE minmax GRANULARITY 1;

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.experiment_items ON CLUSTER '{cluster}'
    MATERIALIZE INDEX idx_experiment_items_id;

--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.experiment_items ON CLUSTER '{cluster}' DROP INDEX IF EXISTS idx_experiment_items_id;

--changeset danield:000152_add_minmax_index_experiment_item_aggregates_id
--comment: Add minmax skip index on experiment_item_aggregates.id so lookups by experiment item id prune granules instead of reading every granule of the workspace (OPIK-9298)

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.experiment_item_aggregates ON CLUSTER '{cluster}'
    ADD INDEX IF NOT EXISTS idx_experiment_item_aggregates_id id TYPE minmax GRANULARITY 1;

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.experiment_item_aggregates ON CLUSTER '{cluster}'
    MATERIALIZE INDEX idx_experiment_item_aggregates_id;

--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.experiment_item_aggregates ON CLUSTER '{cluster}' DROP INDEX IF EXISTS idx_experiment_item_aggregates_id;

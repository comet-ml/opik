--liquibase formatted sql
--changeset andrescrz:000063_add_dataset_item_id_projection
--comment: Add projection sorted by dataset_item_id for primary-key-level performance on stable-ID queries (OPIK-4518)

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.dataset_item_versions ON CLUSTER '{cluster}'
    MODIFY SETTING deduplicate_merge_projection_mode = 'drop',
                   lightweight_mutation_projection_mode = 'drop';

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.dataset_item_versions ON CLUSTER '{cluster}'
    ADD PROJECTION IF NOT EXISTS proj_by_dataset_item_id (
        SELECT *
        ORDER BY (workspace_id, dataset_id, dataset_version_id, dataset_item_id, last_updated_at)
    );

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.dataset_item_versions ON CLUSTER '{cluster}'
    MATERIALIZE PROJECTION proj_by_dataset_item_id;

--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.dataset_item_versions ON CLUSTER '{cluster}' DROP PROJECTION IF EXISTS proj_by_dataset_item_id;
--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.dataset_item_versions ON CLUSTER '{cluster}' MODIFY SETTING deduplicate_merge_projection_mode = 'throw', lightweight_mutation_projection_mode = 'throw';

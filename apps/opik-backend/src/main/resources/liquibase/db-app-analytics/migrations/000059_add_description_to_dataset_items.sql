--liquibase formatted sql
--changeset JetoPistola:000059_add_description_to_dataset_items
--comment: Add description column to dataset_item_versions for evaluation suite test case descriptions

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.dataset_item_versions ON CLUSTER '{cluster}'
    ADD COLUMN IF NOT EXISTS description String DEFAULT '';

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.dataset_item_versions ON CLUSTER '{cluster}'
    ADD COLUMN IF NOT EXISTS description_hash UInt64 MATERIALIZED xxHash64(description);

--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.dataset_item_versions ON CLUSTER '{cluster}' DROP COLUMN IF EXISTS description_hash;
--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.dataset_item_versions ON CLUSTER '{cluster}' DROP COLUMN IF EXISTS description;

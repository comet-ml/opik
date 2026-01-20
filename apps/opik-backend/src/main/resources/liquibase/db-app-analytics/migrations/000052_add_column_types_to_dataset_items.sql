--liquibase formatted sql
--changeset thiagohora:000052_add_column_types_to_dataset_items
--comment: Add materialized column_types column to dataset_items and dataset_item_versions tables for performance optimization of JSON key and type extraction

-- Add column_types to dataset_items table
ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.dataset_items ON CLUSTER '{cluster}'
    ADD COLUMN IF NOT EXISTS column_types Map(String, Array(String)) MATERIALIZED 
        mapFromArrays(
            mapKeys(data),
            arrayMap(key -> [toString(JSONType(data[key]))], mapKeys(data))
        );

-- Add column_types to dataset_item_versions table
ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.dataset_item_versions ON CLUSTER '{cluster}'
    ADD COLUMN IF NOT EXISTS column_types Map(String, Array(String)) MATERIALIZED 
        mapFromArrays(
            mapKeys(data),
            arrayMap(key -> [toString(JSONType(data[key]))], mapKeys(data))
        );

--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.dataset_items ON CLUSTER '{cluster}' DROP COLUMN IF EXISTS column_types;
--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.dataset_item_versions ON CLUSTER '{cluster}' DROP COLUMN IF EXISTS column_types;


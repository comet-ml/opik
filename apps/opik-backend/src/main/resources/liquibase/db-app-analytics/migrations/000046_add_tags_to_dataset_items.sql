--liquibase formatted sql
--changeset idoberko2:000046_add_tags_to_dataset_items
--comment: Add tags column to dataset_items table

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.dataset_items ON CLUSTER '{cluster}'
    ADD COLUMN IF NOT EXISTS tags Array(String) DEFAULT [];

--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.dataset_items ON CLUSTER '{cluster}' DROP COLUMN IF EXISTS tags;


--liquibase formatted sql
--changeset yariv:add_missing_columns_to_annotation_queue_items
--comment: Add missing last_updated_at and last_updated_by columns to annotation_queue_items table

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.annotation_queue_items 
ADD COLUMN last_updated_at DateTime64(6, 'UTC') DEFAULT now64(6);

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.annotation_queue_items 
ADD COLUMN last_updated_by String DEFAULT 'admin';

--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.annotation_queue_items DROP COLUMN last_updated_by;
--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.annotation_queue_items DROP COLUMN last_updated_at;

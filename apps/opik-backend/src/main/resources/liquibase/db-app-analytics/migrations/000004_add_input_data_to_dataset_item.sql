--liquibase formatted sql
--changeset thiagohora:add_input_data_to_dataset_item

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.dataset_items
    ADD COLUMN IF NOT EXISTS data Map(String, String) DEFAULT map();

--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.dataset_items DROP COLUMN data;

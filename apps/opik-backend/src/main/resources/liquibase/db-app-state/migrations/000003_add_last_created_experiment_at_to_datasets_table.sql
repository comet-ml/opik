--liquibase formatted sql
--changeset thiagohora:add_last_created_experiment_at_to_datasets_table

ALTER TABLE datasets ADD COLUMN last_created_experiment_at TIMESTAMP(6) DEFAULT NULL;

--rollback ALTER TABLE datasets DROP COLUMN last_created_experiment_at;

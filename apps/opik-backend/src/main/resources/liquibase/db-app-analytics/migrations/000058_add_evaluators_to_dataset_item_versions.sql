--liquibase formatted sql
--changeset JetoPistola:000058_add_evaluators_to_dataset_item_versions
--comment: Add evaluators and execution_policy columns to dataset_item_versions for evaluation suites

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.dataset_item_versions ON CLUSTER '{cluster}'
    ADD COLUMN IF NOT EXISTS evaluators String DEFAULT '[]',
    ADD COLUMN IF NOT EXISTS execution_policy String DEFAULT '';

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.dataset_item_versions ON CLUSTER '{cluster}'
    ADD COLUMN IF NOT EXISTS evaluators_hash UInt64 MATERIALIZED xxHash64(evaluators),
    ADD COLUMN IF NOT EXISTS execution_policy_hash UInt64 MATERIALIZED xxHash64(execution_policy);

--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.dataset_item_versions ON CLUSTER '{cluster}' DROP COLUMN IF EXISTS evaluators;
--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.dataset_item_versions ON CLUSTER '{cluster}' DROP COLUMN IF EXISTS execution_policy;
--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.dataset_item_versions ON CLUSTER '{cluster}' DROP COLUMN IF EXISTS evaluators_hash;
--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.dataset_item_versions ON CLUSTER '{cluster}' DROP COLUMN IF EXISTS execution_policy_hash;

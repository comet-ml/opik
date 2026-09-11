--liquibase formatted sql
--changeset danield:000049_add_evaluators_to_dataset_versions
--comment: Add evaluators and execution_policy JSON columns to dataset_versions for evaluation suites

ALTER TABLE dataset_versions
    ADD COLUMN evaluators JSON DEFAULT NULL,
    ADD COLUMN execution_policy JSON DEFAULT NULL;

--rollback ALTER TABLE dataset_versions DROP COLUMN evaluators, DROP COLUMN execution_policy;

--liquibase formatted sql
--changeset BorisTkachenko:000007_add_change_description_metadata_to_prompt_version

ALTER TABLE prompt_versions ADD COLUMN change_description MEDIUMTEXT DEFAULT NULL;
ALTER TABLE prompt_versions ADD COLUMN metadata JSON DEFAULT NULL;

--rollback ALTER TABLE prompt_versions DROP COLUMN change_description;
--rollback ALTER TABLE prompt_versions DROP COLUMN metadata;

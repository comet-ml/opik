--liquibase formatted sql
--changeset BorisTkachenko:000019_add_tags_to_prompts

ALTER TABLE prompts ADD COLUMN tags MEDIUMTEXT DEFAULT NULL;

--rollback ALTER TABLE prompts DROP COLUMN tags;

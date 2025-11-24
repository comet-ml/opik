--liquibase formatted sql
--changeset andrescrz:000040_add_tags_to_prompt_versions

ALTER TABLE prompt_versions
    ADD COLUMN tags MEDIUMTEXT DEFAULT NULL;

--rollback ALTER TABLE prompt_versions DROP COLUMN tags;

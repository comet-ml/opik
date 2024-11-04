--liquibase formatted sql
--changeset thiagohora:increate_prompt_version_commit_length

ALTER TABLE prompt_versions MODIFY COLUMN commit VARCHAR(8);

--rollback ALTER TABLE prompt_versions MODIFY COLUMN commit VARCHAR(7);

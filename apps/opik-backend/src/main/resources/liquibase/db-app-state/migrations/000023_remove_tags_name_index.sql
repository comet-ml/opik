--liquibase formatted sql
--changeset TagsManagement:000023_remove_tags_name_index

DROP INDEX idx_tags_name ON tags;

--rollback CREATE INDEX idx_tags_name ON tags (name);
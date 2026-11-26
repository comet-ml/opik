--liquibase formatted sql
--changeset yaroslavb:000127_add_topic_projects_rebuild_uncached
--comment: Topics list recovery: a reset list is rebuilt without the inference cache, and a list records its author task version (OPIK-8987)

ALTER TABLE topic_projects
    ADD COLUMN rebuild_uncached BOOLEAN NOT NULL DEFAULT FALSE AFTER embed_model,
    ADD COLUMN list_author_version INT NULL AFTER rebuild_uncached;

--rollback ALTER TABLE topic_projects DROP COLUMN list_author_version, DROP COLUMN rebuild_uncached;

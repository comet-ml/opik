--liquibase formatted sql
--changeset thiagohora:000048_add_index_tag_version
--comment: Add composite index on dataset_version_tags(tag, version_id) to optimize findLatestVersionsByDatasetIds query
-- 
-- The composite index (tag, version_id) provides:
-- 1. Efficient filtering by tag (ref access type instead of index scan)
-- 2. Efficient grouping by version_id (covering index)

CREATE INDEX idx_dataset_version_tags_tag_version ON dataset_version_tags(tag, version_id);

--rollback DROP INDEX idx_dataset_version_tags_tag_version ON dataset_version_tags;

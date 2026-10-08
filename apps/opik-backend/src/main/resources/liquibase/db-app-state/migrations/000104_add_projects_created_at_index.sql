--liquibase formatted sql
--changeset thiagohora:000104_add_projects_created_at_index
--comment: Index projects by created_at so MIN(created_at), the partition-metrics floor, reads one index entry instead of the whole table (OPIK-8631)

-- The partition-health metrics floor weekly partitions at the install date, MIN(projects.created_at). Without
-- an index that is a full scan: measured on production at 704k rows / 130 MB, 384ms.
CREATE INDEX projects_created_at_idx ON projects(created_at);

--rollback DROP INDEX projects_created_at_idx ON projects;

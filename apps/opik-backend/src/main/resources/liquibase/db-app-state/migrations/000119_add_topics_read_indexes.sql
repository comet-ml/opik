--liquibase formatted sql
--changeset yaroslavb:000119_add_projects_workspace_last_updated_trace_at_idx
--comment: Index the topics first build's read of recently active projects (OPIK-8987)

-- The first build reads a workspace's projects with a trace since a cutoff; most projects of a large workspace are
-- dormant. With this index that read is a covering range scan of the recent ones (InnoDB appends the primary key id).
CREATE INDEX projects_workspace_last_updated_trace_at_idx ON projects (workspace_id, last_updated_trace_at);

--rollback DROP INDEX projects_workspace_last_updated_trace_at_idx ON projects;

--changeset yaroslavb:000119_add_topic_projects_workspace_labels_idx
--comment: Index the topics first build's read of saved lists whose labels are not complete (OPIK-8987)

-- labels_complete defaults to FALSE, so it also matches every row without a list; list_run_id narrows to saved lists.
CREATE INDEX topic_projects_workspace_labels_idx ON topic_projects (workspace_id, labels_complete, list_run_id);

--rollback DROP INDEX topic_projects_workspace_labels_idx ON topic_projects;

--changeset yaroslavb:000119_replace_topics_workspace_project_idx
--comment: Serve a project's topics in their order from the index (OPIK-8987)

-- The project's topics are read ORDER BY born, id; InnoDB appends id, so this index serves the order with no sort.
ALTER TABLE topics
    DROP INDEX topics_workspace_project_idx,
    ADD INDEX topics_workspace_project_born_idx (workspace_id, project_id, born);

--rollback ALTER TABLE topics DROP INDEX topics_workspace_project_born_idx, ADD INDEX topics_workspace_project_idx (workspace_id, project_id);

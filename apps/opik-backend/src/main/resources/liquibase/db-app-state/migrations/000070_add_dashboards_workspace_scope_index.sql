--liquibase formatted sql
--changeset thiagohora:000070_add_dashboards_workspace_scope_index
--comment: Add covering index to satisfy dashboards list ORDER BY id DESC from index order and avoid filesort on SELECT * with JSON config (OPIK-6482)

CREATE INDEX dashboards_workspace_scope_id_idx ON dashboards (workspace_id, scope, id);

--rollback DROP INDEX dashboards_workspace_scope_id_idx ON dashboards;


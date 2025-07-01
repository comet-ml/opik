--liquibase formatted sql
--changeset thiagohora:000030_add_project_configs_table

CREATE TABLE IF NOT EXISTS ${ANALYTICS_DB_DATABASE_NAME}.project_configurations ON CLUSTER '{cluster}' (
    workspace_id                                String,
    project_id                                  FixedString(36),
    timeout_mark_thread_as_inactive             Time,
    created_at                                  DateTime64(9, 'UTC')        DEFAULT now64(9),
    last_updated_at                             DateTime64(6, 'UTC')        DEFAULT now64(6),
    created_by                                  String                      DEFAULT '',
    last_updated_by                             String                      DEFAULT ''
) ENGINE = ReplicatedReplacingMergeTree('/clickhouse/tables/{shard}/${ANALYTICS_DB_DATABASE_NAME}/project_configurations', '{replica}', last_updated_at)
ORDER BY (workspace_id, project_id);

--rollback DROP TABLE IF EXISTS ${ANALYTICS_DB_DATABASE_NAME}.project_configurations ON CLUSTER '{cluster}';

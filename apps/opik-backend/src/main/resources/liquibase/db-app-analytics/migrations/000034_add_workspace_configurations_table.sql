--liquibase formatted sql
--changeset thiagohora:000034_add_workspace_configurations_table

CREATE TABLE IF NOT EXISTS ${ANALYTICS_DB_DATABASE_NAME}.workspace_configurations ON CLUSTER '{cluster}' (
    workspace_id                    String,
    timeout_mark_thread_as_inactive UInt32, -- timeout in seconds, 0 means no timeout
    created_at                      DateTime64(9, 'UTC') DEFAULT now64(9),
    created_by                      String DEFAULT 'admin',
    last_updated_at                 DateTime64(6, 'UTC') DEFAULT now64(6),
    last_updated_by                 String DEFAULT 'admin'
)
ENGINE = ReplicatedReplacingMergeTree('/clickhouse/tables/{shard}/${ANALYTICS_DB_DATABASE_NAME}/workspace_configurations', '{replica}', last_updated_at)
ORDER BY workspace_id;

--rollback DROP TABLE IF EXISTS ${ANALYTICS_DB_DATABASE_NAME}.workspace_configurations ON CLUSTER '{cluster}'; 

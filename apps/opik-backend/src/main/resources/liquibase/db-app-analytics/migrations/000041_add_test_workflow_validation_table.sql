--liquibase formatted sql
--changeset andrescrz:000041_add_test_workflow_validation_table
--comment: Add test table to validate ClickHouse migration workflow detection for OPIK-2414

-- Create test table for workflow validation
CREATE TABLE IF NOT EXISTS test_workflow_validation ON CLUSTER '{cluster}' (
    id              FixedString(36),
    test_name       String,
    created_at      DateTime64(9, 'UTC') DEFAULT now64(9),
    created_by      String DEFAULT 'workflow-test',
    last_updated_at DateTime64(6, 'UTC') DEFAULT now64(6),
    last_updated_by String DEFAULT 'workflow-test'
)
ENGINE = ReplicatedReplacingMergeTree('/clickhouse/tables/{shard}/${ANALYTICS_DB_DATABASE_NAME}/test_workflow_validation', '{replica}', last_updated_at)
ORDER BY (test_name, id);

-- Index for test queries
ALTER TABLE test_workflow_validation ON CLUSTER '{cluster}' 
ADD INDEX idx_test_name test_name TYPE bloom_filter GRANULARITY 1;

--rollback DROP TABLE ${ANALYTICS_DB_DATABASE_NAME}.test_workflow_validation ON CLUSTER '{cluster}';


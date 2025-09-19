--liquibase formatted sql
--changeset andrescrz:000042_failing_test_migration
--comment: Test migration that should FAIL validation - missing ON CLUSTER clauses

-- This table creation is missing ON CLUSTER '{cluster}' - should FAIL
CREATE TABLE IF NOT EXISTS failing_test_table (
    id              FixedString(36),
    test_name       String,
    created_at      DateTime64(9, 'UTC') DEFAULT now64(9),
    created_by      String DEFAULT 'test',
    last_updated_at DateTime64(6, 'UTC') DEFAULT now64(6),
    last_updated_by String DEFAULT 'test'
)
ENGINE = ReplicatedReplacingMergeTree('/clickhouse/tables/{shard}/${ANALYTICS_DB_DATABASE_NAME}/failing_test_table', '{replica}', last_updated_at)
ORDER BY (test_name, id);

-- This ALTER is also missing ON CLUSTER '{cluster}' - should FAIL
ALTER TABLE failing_test_table
ADD INDEX idx_failing_test test_name TYPE bloom_filter GRANULARITY 1;

--rollback
-- DROP TABLE IF EXISTS failing_test_table ON CLUSTER '{cluster}';

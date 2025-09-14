--liquibase formatted sql
--changeset yariv:add_sme_annotation_progress
--comment: Add SME annotation progress tracking for individual user progress

-- Create SME annotation progress tracking table
CREATE TABLE IF NOT EXISTS ${ANALYTICS_DB_DATABASE_NAME}.sme_annotation_progress
(
    workspace_id        String,
    queue_id            FixedString(36),
    sme_identifier      String,
    item_id             FixedString(36),
    item_type           Enum8('trace' = 0, 'thread' = 1),
    status              Enum8('pending' = 0, 'completed' = 1, 'skipped' = 2),
    feedback_scores     String,
    comment             String,
    created_at          DateTime64(9, 'UTC') DEFAULT now64(9),
    last_updated_at     DateTime64(9, 'UTC') DEFAULT now64(9),
    created_by          String DEFAULT 'sme',
    last_updated_by     String DEFAULT 'sme'
)
ENGINE = ReplicatedReplacingMergeTree('/clickhouse/tables/{shard}/${ANALYTICS_DB_DATABASE_NAME}/sme_annotation_progress', '{replica}', last_updated_at)
ORDER BY (workspace_id, queue_id, sme_identifier, item_id)
PRIMARY KEY (workspace_id, queue_id, sme_identifier, item_id)
SETTINGS index_granularity = 8192;

-- Index for SME-specific queries (finding SME's progress in a queue)
ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.sme_annotation_progress 
ADD INDEX idx_workspace_queue_sme (workspace_id, queue_id, sme_identifier) TYPE bloom_filter GRANULARITY 4;

-- Index for queue-level aggregations (overall progress)
ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.sme_annotation_progress 
ADD INDEX idx_workspace_queue_status (workspace_id, queue_id, status) TYPE bloom_filter GRANULARITY 4;

--rollback DROP TABLE IF EXISTS ${ANALYTICS_DB_DATABASE_NAME}.sme_annotation_progress;




    --liquibase formatted sql
    --changeset yariv:add_annotation_queues_sharing
    --comment: Add sharing functionality to annotation queues for SME access

    -- Add sharing fields to annotation queues table
    ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.annotation_queues
    ADD COLUMN share_token Nullable(FixedString(36)),
    ADD COLUMN is_public UInt8 DEFAULT 0;

    -- Index for share token lookups (used by SME public endpoints)
    ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.annotation_queues
    ADD INDEX idx_share_token (share_token) TYPE bloom_filter GRANULARITY 4;

    --rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.annotation_queues DROP COLUMN share_token;
    --rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.annotation_queues DROP COLUMN is_public;


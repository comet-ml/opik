--liquibase formatted sql
--changeset boryst:000088_create_cipx_user_mappings
--comment: Create cipx_user_mappings — user_email -> user_uuid lookup for Cost Intelligence retrieval

-- Populated alongside cipx_trace_identities from the cipx trace identity (INSERT IGNORE, the pair is
-- the primary key). Retrieval resolves a caller's email to user_uuid(s) here first, then filters
-- ClickHouse by user_uuid only (user_uuid is the cipx_trace_identities primary-key prefix). One email
-- may map to several uuids over time, hence the composite key and IN-based lookups.
CREATE TABLE IF NOT EXISTS cipx_user_mappings (
    user_email VARCHAR(512) NOT NULL,
    user_uuid VARCHAR(255) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT cipx_user_mappings_pk PRIMARY KEY (user_email, user_uuid)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

--rollback DROP TABLE IF EXISTS cipx_user_mappings;

--liquibase formatted sql
--changeset boryst:000110_add_content_sha256_to_cipx_spend_blocks
--comment: Persist the per-block content hash on cipx_spend_blocks (OPIK-7457)

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.cipx_spend_blocks ON CLUSTER '{cluster}'
    ADD COLUMN IF NOT EXISTS content_sha256 String DEFAULT '';

--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.cipx_spend_blocks ON CLUSTER '{cluster}' DROP COLUMN IF EXISTS content_sha256;


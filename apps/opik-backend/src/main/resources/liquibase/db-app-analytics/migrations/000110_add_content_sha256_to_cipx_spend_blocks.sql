--liquibase formatted sql
--changeset boryst:000110_add_content_sha256_to_cipx_spend_blocks
--comment: Persist the per-block content hash on cipx_spend_blocks (OPIK-7457)

-- The proxy already emits a per-block sha256 over the raw block content (utf8 for text,
-- decoded bytes for binary); it was dropped at ingestion. Persisting it gives the breakdown
-- queries a stable content identity so item counts can be uniqExact(content_sha256) — a
-- block re-sent (and re-billed) on later turns shares its hash, so replays and turn
-- fragmentation collapse to one distinct item. Existing rows read the default ''.
ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.cipx_spend_blocks ON CLUSTER '{cluster}'
    ADD COLUMN IF NOT EXISTS content_sha256 String DEFAULT '';

--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.cipx_spend_blocks ON CLUSTER '{cluster}' DROP COLUMN IF EXISTS content_sha256;


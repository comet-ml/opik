--liquibase formatted sql
--changeset petrotiurin:000101_add_query_source_to_cipx_spend
--comment: Add query_source (cipx.call.query_source) to cipx_spends so AI-spend levers can scope to the main conversation loop

-- cipx emits metadata.cipx.call.query_source ('main' for the primary conversation loop, something
-- else for subagent/auxiliary calls). The "switch default model" savings lever needs to scope to
-- 'main' only -- subagent calls aren't affected by changing the default model setting -- so this
-- needs its own typed column rather than being folded into an existing one.
ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.cipx_spends ON CLUSTER '{cluster}'
    ADD COLUMN IF NOT EXISTS query_source LowCardinality(String) DEFAULT '';

--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.cipx_spends ON CLUSTER '{cluster}' DROP COLUMN IF EXISTS query_source;

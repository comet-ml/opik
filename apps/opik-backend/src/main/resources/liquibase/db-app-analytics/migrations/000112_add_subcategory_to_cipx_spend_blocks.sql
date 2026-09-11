--liquibase formatted sql
--changeset petrot:000112_add_subcategory_to_cipx_spend_blocks
--comment: Persist the per-block subcategory (which variant of its category a block is) on cipx_spend_blocks
--
-- Category-scoped value set, mirroring cipx wire.Block.Subcategory:
--   memory -> 'auto_memory' | 'project_instructions' | 'rule' | 'user_global'
-- '' means unknown: every row written before cipx emitted the field, plus any
-- category with no variant concept. Readers must treat '' as "can't tell"
-- rather than as an implicit default -- the savings levers price it with their
-- pre-subcategory whole-lane weight instead of attributing it to one variant.

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.cipx_spend_blocks ON CLUSTER '{cluster}'
    ADD COLUMN IF NOT EXISTS subcategory LowCardinality(String) DEFAULT '';

--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.cipx_spend_blocks ON CLUSTER '{cluster}' DROP COLUMN IF EXISTS subcategory;

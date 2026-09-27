--liquibase formatted sql
--changeset boryst:000127_add_kind_to_cipx_session_analysis_tasks
--comment: Per-task kind (feature | bug | doc | investigation | other) for the cost API session analysis (OPIK-8521)

-- One more parallel array in the `tasks` nested structure. Rows written before this
-- column existed read '' per task (ClickHouse sizes a missing nested sibling to its
-- siblings), which the cost API treats as "unclassified"; they are re-analysed at the
-- bumped task version rather than backfilled.
ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.cipx_session_analysis ON CLUSTER '{cluster}'
    ADD COLUMN IF NOT EXISTS `tasks.kind` Array(LowCardinality(String));

--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.cipx_session_analysis ON CLUSTER '{cluster}' DROP COLUMN IF EXISTS `tasks.kind`;

--liquibase formatted sql
--changeset boryst:000128_add_outcome_to_cipx_session_analysis_tasks
--comment: Per-task outcome (completed | abandoned | unclear) for the cost API session analysis (OPIK-8522)

-- One more parallel array in the `tasks` nested structure, beside `tasks.kind`. Rows
-- written before this column existed read '' per task, which the cost API hides behind
-- its version gate; they are re-analysed at the bumped task version, not backfilled.
ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.cipx_session_analysis ON CLUSTER '{cluster}'
    ADD COLUMN IF NOT EXISTS `tasks.outcome` Array(LowCardinality(String));

--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.cipx_session_analysis ON CLUSTER '{cluster}' DROP COLUMN IF EXISTS `tasks.outcome`;

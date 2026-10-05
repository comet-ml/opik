--liquibase formatted sql
--changeset petrot:000129_add_skills_to_cipx_session_analysis
--comment: Per-skill turn ranges and skill-pass version for the cost API session analysis (OPIK-8522)

-- `skills` is a nested group, one row per attributed range (slug, origin, trigger, ordinal, turns, trace ids, judge_status, description, summary, tokens, cost_usd); `skills_version` is the task version whose skill pass completed (0 = not run).
-- Per-range tokens and cost plus `session_first_turn_at` / `session_last_turn_at` feed leaderboards and give them a time dimension.
-- Rows written before these columns existed read empty arrays / 0, which the cost API treats as "skills not analysed".
ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.cipx_session_analysis ON CLUSTER '{cluster}'
    ADD COLUMN IF NOT EXISTS `skills.slug`            Array(String),
    ADD COLUMN IF NOT EXISTS `skills.origin`          Array(LowCardinality(String)),
    ADD COLUMN IF NOT EXISTS `skills.trigger`         Array(LowCardinality(String)),
    ADD COLUMN IF NOT EXISTS `skills.ordinal`         Array(UInt16),
    ADD COLUMN IF NOT EXISTS `skills.activation_turn` Array(UInt32),
    ADD COLUMN IF NOT EXISTS `skills.start_turn`      Array(UInt32),
    ADD COLUMN IF NOT EXISTS `skills.end_turn`        Array(UInt32),
    ADD COLUMN IF NOT EXISTS `skills.first_trace_id`  Array(FixedString(36)),
    ADD COLUMN IF NOT EXISTS `skills.last_trace_id`   Array(FixedString(36)),
    ADD COLUMN IF NOT EXISTS `skills.judge_status`    Array(LowCardinality(String)),
    ADD COLUMN IF NOT EXISTS `skills.description`     Array(String),
    ADD COLUMN IF NOT EXISTS `skills.summary`         Array(String),
    ADD COLUMN IF NOT EXISTS `skills.tokens`          Array(UInt64),
    ADD COLUMN IF NOT EXISTS `skills.cost_usd`        Array(Float64),
    ADD COLUMN IF NOT EXISTS skills_version           UInt16 DEFAULT 0,
    ADD COLUMN IF NOT EXISTS session_first_turn_at    DateTime64(6, 'UTC') DEFAULT toDateTime64(0, 6),
    ADD COLUMN IF NOT EXISTS session_last_turn_at     DateTime64(6, 'UTC') DEFAULT toDateTime64(0, 6);

--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.cipx_session_analysis ON CLUSTER '{cluster}' DROP COLUMN IF EXISTS `skills.slug`, DROP COLUMN IF EXISTS `skills.origin`, DROP COLUMN IF EXISTS `skills.trigger`, DROP COLUMN IF EXISTS `skills.ordinal`, DROP COLUMN IF EXISTS `skills.activation_turn`, DROP COLUMN IF EXISTS `skills.start_turn`, DROP COLUMN IF EXISTS `skills.end_turn`, DROP COLUMN IF EXISTS `skills.first_trace_id`, DROP COLUMN IF EXISTS `skills.last_trace_id`, DROP COLUMN IF EXISTS `skills.judge_status`, DROP COLUMN IF EXISTS `skills.description`, DROP COLUMN IF EXISTS `skills.summary`, DROP COLUMN IF EXISTS `skills.tokens`, DROP COLUMN IF EXISTS `skills.cost_usd`, DROP COLUMN IF EXISTS skills_version, DROP COLUMN IF EXISTS session_first_turn_at, DROP COLUMN IF EXISTS session_last_turn_at;

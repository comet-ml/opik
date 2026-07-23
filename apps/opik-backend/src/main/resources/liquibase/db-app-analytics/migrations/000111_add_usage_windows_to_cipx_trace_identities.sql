--liquibase formatted sql
--changeset aadereiko:000111_add_usage_windows_to_cipx_trace_identities
--comment: Add rolling usage-window columns to cipx_trace_identities (Anthropic 5h/7d utilization + reset, for the Spend & Activity limits meter)

-- The proxy now captures the raw rolling rate-limit windows from /api/oauth/usage (previously it kept
-- only the reduced within/over status). Each window carries utilization (percent-of-limit consumed,
-- 0-100) and resets_at (ISO-8601 timestamp when the window rolls over). Identity is trace-level, so
-- these land alongside plan / billing_mode / plan_usage_status (000104). Additive columns with
-- defaults; existing rows read the default. resets_at is stored as the raw ISO-8601 String (the UI
-- renders a countdown from it; we never do date math on it in ClickHouse — row recency uses
-- start_time). An absent window (api pricing, gateway-scrubbed, or unfetched) leaves utilization 0 and
-- resets_at ''.
ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.cipx_trace_identities ON CLUSTER '{cluster}'
    ADD COLUMN IF NOT EXISTS five_hour_utilization  Float64 DEFAULT 0,
    ADD COLUMN IF NOT EXISTS five_hour_resets_at     String  DEFAULT '',
    ADD COLUMN IF NOT EXISTS seven_day_utilization  Float64 DEFAULT 0,
    ADD COLUMN IF NOT EXISTS seven_day_resets_at     String  DEFAULT '';

--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.cipx_trace_identities ON CLUSTER '{cluster}' DROP COLUMN IF EXISTS five_hour_utilization, DROP COLUMN IF EXISTS five_hour_resets_at, DROP COLUMN IF EXISTS seven_day_utilization, DROP COLUMN IF EXISTS seven_day_resets_at;
